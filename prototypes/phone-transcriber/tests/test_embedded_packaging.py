"""Small synthetic packaging fixtures, CI only; no model inference/download."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import prepare_embedded_models as prepare
from check_apk import verify_embedded
from export_provenance import describe


class EmbeddedPackagingTests(unittest.TestCase):
    def fixture(self, root, corrupt=False, missing=False, compressed=False):
        data = {"acoustic/asr.onnx": b"asr", "acoustic/manifest.json": b"{}",
                "acoustic/smoke-report.json": b"passed", "text-model/model_int8.onnx": b"text",
                "text-model/NOTICE.txt": prepare.NOTICE}
        rec = lambda value: {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}
        pin = {"acoustic_model_id": "frozen", "acoustic_files": {"asr.onnx": rec(data["acoustic/asr.onnx"])},
               "text_model": {"repository": "fixture", "revision": "pinned", "files": {"model_int8.onnx": rec(data["text-model/model_int8.onnx"])}}}
        pin_path = root / "pin.json"
        pin_path.write_text(json.dumps(pin))
        bundle = {"schema_version": 1, "acoustic_model_id": "frozen", "text_repository": "fixture", "text_revision": "pinned",
                  "source_pin_sha256": hashlib.sha256(pin_path.read_bytes()).hexdigest(), "files": {k: rec(v) for k, v in data.items()}}
        bundle["export_validation"] = describe(
            rec(data["acoustic/manifest.json"])["sha256"], {"commit": "a" * 40, "run_id": "123", "run_attempt": "1"})
        (root / "bundle.json").write_text(json.dumps(bundle))
        archive = root / "fixture.apk"
        with zipfile.ZipFile(archive, "w") as apk:
            apk.writestr("assets/models/bundle.json", json.dumps(bundle))
            for name, value in data.items():
                if missing and name.endswith("NOTICE.txt"):
                    continue
                if corrupt and name == "acoustic/asr.onnx":
                    value = b"bad"
                apk.writestr("assets/models/" + name, value, compress_type=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED)
        return archive, pin_path

    def test_required_payloads_are_verified_independently_of_archive_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, pin = self.fixture(root)
            with zipfile.ZipFile(archive) as apk:
                self.assertIn("assets/models/acoustic/asr.onnx", verify_embedded(apk, root, pin))

    def test_missing_corrupt_and_compressed_model_are_rejected(self):
        for flags in ({"corrupt": True}, {"missing": True}, {"compressed": True}):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, pin = self.fixture(root, **flags)
                with zipfile.ZipFile(archive) as apk, self.assertRaises(ValueError):
                    verify_embedded(apk, root, pin)

    def test_source_pin_cannot_be_silently_changed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, pin = self.fixture(root)
            pin.write_text(pin.read_text() + " ")
            with zipfile.ZipFile(archive) as apk, self.assertRaisesRegex(ValueError, "source pin"):
                verify_embedded(apk, root, pin)

    def test_apk_inspection_rejects_missing_stale_or_unrelated_export_provenance(self):
        for change in ("missing", "inputs", "manifest"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, pin = self.fixture(root)
                with zipfile.ZipFile(archive) as apk:
                    entries = {item.filename: apk.read(item) for item in apk.infolist()}
                bundle = json.loads(entries["assets/models/bundle.json"])
                if change == "missing":
                    del bundle["export_validation"]
                elif change == "inputs":
                    bundle["export_validation"]["inputs_sha256"] = "0" * 64
                else:
                    bundle["export_validation"]["manifest_sha256"] = "0" * 64
                content = json.dumps(bundle).encode()
                (root / "bundle.json").write_bytes(content)
                entries["assets/models/bundle.json"] = content
                with zipfile.ZipFile(archive, "w") as apk:
                    for name, value in entries.items():
                        apk.writestr(name, value)
                with zipfile.ZipFile(archive) as apk, self.assertRaises(ValueError):
                    verify_embedded(apk, root, pin)

    def test_pinned_text_contract_matches_kotlin(self):
        pin = json.loads(prepare.PIN.read_text())["text_model"]
        kotlin = (ROOT / "android/app/src/main/java/com/pmixay/ascent/OnnxTextModel.kt").read_text()
        for value in (pin["repository"], pin["revision"], pin["files"]["model_int8.onnx"]["sha256"], pin["files"]["tokenizer.json"]["sha256"]):
            self.assertIn(value, kotlin)
        for constant, name in (("MODEL_BYTES", "model_int8.onnx"), ("TOKENIZER_BYTES", "tokenizer.json")):
            actual = re.search(rf'const val {constant} = ([0-9_]+)L', kotlin)
            self.assertEqual(int(actual[1].replace("_", "")), pin["files"][name]["bytes"])

    def test_transport_rejects_non_https(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "HTTPS"):
            prepare.download("http://example.invalid/model", Path(directory) / "model", {"bytes": 1, "sha256": "0" * 64})

    def test_download_checks_actual_bytes_and_cleans_unverified_staging(self):
        expected = {"bytes": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}
        for payload in (b"bad", b"truncated too long", b"a"):
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                opener = Mock()
                opener.open.return_value = io.BytesIO(payload)
                with patch("prepare_embedded_models.urllib.request.build_opener", return_value=opener), self.assertRaises(ValueError):
                    prepare.download("https://example.invalid/model", root / "model", expected)
                self.assertEqual(list(root.iterdir()), [])

    def test_verified_download_cache_works_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model"
            path.write_bytes(b"abc")
            expected = {"bytes": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}
            with patch("prepare_embedded_models.urllib.request.build_opener") as connection:
                prepare.download("https://example.invalid/model", path, expected)
                connection.assert_not_called()


if __name__ == "__main__":
    unittest.main()
