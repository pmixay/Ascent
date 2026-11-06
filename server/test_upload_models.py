"""Model publication integrity checks, executed only in GitHub Actions."""
import hashlib
import gzip
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1] / "prototypes/phone-transcriber/tools"
sys.path.insert(0, str(TOOLS))

from model_pack import MODEL_ID, SPEAKER_MODEL_ID
from upload_models import publish


def archive_fixture(*, report=None, missing_report=False, corrupt=False, traversal=False, asr=b"asr-bytes"):
    if report is None:
        report = {"status": "passed", "speaker_runtime_tested": True,
                  "speech_files_tested": 1, "speaker_smoke": {"status": "passed"}}
    files = {
        "asr.onnx": asr,
        "speaker.onnx": b"speaker-bytes",
        "tokens.json": json.dumps(["a", "b", " "]).encode(),
        "LICENSE-asr.txt": b"Model license fixture",
        "LICENSE-speaker.txt": b"Speaker license fixture",
        "smoke-report.json": json.dumps(report).encode(),
    }
    manifest = {
        "schema_version": 1, "model_id": MODEL_ID,
        "sample_rate": 16000, "channels": 1, "min_samples": 4000, "max_samples": 400000,
        "asr_file": "asr.onnx", "tokens_file": "tokens.json", "speaker_file": "speaker.onnx",
        "speaker_model_id": SPEAKER_MODEL_ID, "speaker_runtime": "sherpa-onnx",
        "vocabulary_size": 3, "blank_id": 3,
        "files": {name: {"sha256": hashlib.sha256(value).hexdigest(), "bytes": len(value)}
                  for name, value in files.items()},
    }
    manifest_bytes = json.dumps(manifest, sort_keys=True).encode()
    if corrupt:
        files["asr.onnx"] = b"bad-bytes"
    if missing_report:
        files.pop("smoke-report.json")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("manifest.json", manifest_bytes)
        for name, content in files.items():
            archive.writestr(name, content)
        if traversal:
            archive.writestr("../escaped.onnx", b"must not escape")
    return buffer.getvalue(), manifest_bytes, files


class ModelPublicationTests(unittest.TestCase):
    def assert_staging_clean(self, root):
        self.assertEqual(list((root.parent / ".incoming-models").iterdir()), [])
        self.assertEqual(list(root.glob(".catalog-*")), [])

    def test_validated_files_publish_to_the_manifest_digest_and_are_idempotent(self):
        archive, manifest_bytes, files = archive_fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "models"
            catalog = publish(io.BytesIO(archive), root)
            digest = hashlib.sha256(manifest_bytes).hexdigest()
            self.assertEqual(catalog["manifest_sha256"], digest)
            self.assertEqual(catalog["manifest_bytes"], len(manifest_bytes))
            self.assertEqual(catalog["model_id"], MODEL_ID)
            self.assertEqual(json.loads((root / "catalog.json").read_text()), catalog)
            revision = root / MODEL_ID / digest
            self.assertEqual({entry.name for entry in revision.iterdir()}, set(files) | {"manifest.json"})
            self.assertEqual((revision / "manifest.json").read_bytes(), manifest_bytes)
            for name, content in files.items():
                self.assertEqual((revision / name).read_bytes(), content)
            downloads = root / MODEL_ID / "downloads" / catalog["download_sha256"]
            index_bytes = (downloads / "download.json").read_bytes()
            self.assertEqual(len(index_bytes), catalog["download_bytes"])
            self.assertEqual(hashlib.sha256(index_bytes).hexdigest(), catalog["download_sha256"])
            index = json.loads(index_bytes)
            self.assertEqual(index["manifest_sha256"], digest)
            for name, records in index["files"].items():
                restored = b""
                for number, record in enumerate(records):
                    compressed = (downloads / f"{name}.{number}.gz").read_bytes()
                    self.assertEqual(len(compressed), record["bytes"])
                    self.assertEqual(hashlib.sha256(compressed).hexdigest(), record["sha256"])
                    raw = gzip.decompress(compressed)
                    self.assertEqual(len(raw), record["raw_bytes"])
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), record["raw_sha256"])
                    restored += raw
                self.assertEqual(restored, files[name])
            self.assertEqual(publish(io.BytesIO(archive), root), catalog)
            self.assertEqual(len(list((root / MODEL_ID).iterdir())), 2)
            self.assert_staging_clean(root)

    def test_failed_or_missing_smoke_report_preserves_the_previous_catalog(self):
        cases = (
            {"report": {"status": "failed", "speaker_runtime_tested": True, "speech_files_tested": 1}},
            {"report": {"status": "passed", "speaker_runtime_tested": False, "speech_files_tested": 1}},
            {"report": {"status": "passed", "speaker_runtime_tested": True, "speech_files_tested": True}},
            {"missing_report": True},
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "models"
            publish(io.BytesIO(archive_fixture()[0]), root)
            previous = (root / "catalog.json").read_bytes()
            for case in cases:
                with self.subTest(case=case):
                    with self.assertRaises(ValueError):
                        publish(io.BytesIO(archive_fixture(**case)[0]), root)
                    self.assertEqual((root / "catalog.json").read_bytes(), previous)
                    self.assertEqual(len(list((root / MODEL_ID).iterdir())), 2)
                    self.assert_staging_clean(root)

    def test_large_model_is_split_into_independently_decompressible_chunks(self):
        raw = b"a" * (4 * 1024 * 1024) + b"last chunk"
        archive, _, _ = archive_fixture(asr=raw)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "models"
            catalog = publish(io.BytesIO(archive), root)
            downloads = root / MODEL_ID / "downloads" / catalog["download_sha256"]
            index = json.loads((downloads / "download.json").read_text())
            self.assertEqual(len(index["files"]["asr.onnx"]), 2)
            self.assertEqual(gzip.decompress((downloads / "asr.onnx.0.gz").read_bytes()), raw[:4 * 1024 * 1024])
            self.assertEqual(gzip.decompress((downloads / "asr.onnx.1.gz").read_bytes()), b"last chunk")
            previous = (root / "catalog.json").read_bytes()
            (downloads / "asr.onnx.0.gz").write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                publish(io.BytesIO(archive), root)
            self.assertEqual((root / "catalog.json").read_bytes(), previous)
            self.assert_staging_clean(root)

    def test_corrupt_payload_and_traversal_fail_without_publishing_or_leaking_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "models"
            publish(io.BytesIO(archive_fixture()[0]), root)
            previous = (root / "catalog.json").read_bytes()
            for case in ({"corrupt": True}, {"traversal": True}):
                with self.subTest(case=case):
                    with self.assertRaises(ValueError):
                        publish(io.BytesIO(archive_fixture(**case)[0]), root)
                    self.assertEqual((root / "catalog.json").read_bytes(), previous)
                    self.assertEqual(len(list((root / MODEL_ID).iterdir())), 2)
                    self.assertFalse((root.parent / "escaped.onnx").exists())
                    self.assert_staging_clean(root)


if __name__ == "__main__":
    unittest.main()
