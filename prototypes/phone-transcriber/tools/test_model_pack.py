"""Run with: python -m unittest discover -s tools -p 'test_*.py'."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from model_pack import MODEL_ID, REQUIRED_FILES, SPEAKER_MODEL_ID, ctc_decode, load_json, validate_zip


class ModelPackTests(unittest.TestCase):
    def fixture(self):
        content = {name: b"fixture" for name in REQUIRED_FILES}
        content["tokens.json"] = json.dumps(["а", "б", " "], ensure_ascii=False).encode()
        manifest = {
            "schema_version": 1, "model_id": MODEL_ID,
            "sample_rate": 16000, "channels": 1, "min_samples": 4000, "max_samples": 400000,
            "asr_file": "asr.onnx", "tokens_file": "tokens.json", "speaker_file": "speaker.onnx",
            "speaker_model_id": SPEAKER_MODEL_ID, "speaker_runtime": "sherpa-onnx",
            "blank_id": 3, "vocabulary_size": 3,
            "files": {name: {"sha256": hashlib.sha256(value).hexdigest(), "bytes": len(value)}
                      for name, value in content.items()},
        }
        return content, manifest

    def verify(self, content, manifest, extra=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pack.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                for name, value in content.items():
                    archive.writestr(name, value)
                if extra:
                    archive.writestr(*extra)
            return validate_zip(path)

    def test_valid_manifest_and_hashes(self):
        content, manifest = self.fixture()
        self.assertEqual(self.verify(content, manifest)["model_id"], MODEL_ID)

    def test_corrupt_model_rejected_even_when_length_matches(self):
        content, manifest = self.fixture()
        content["asr.onnx"] = b"corrupt"
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.verify(content, manifest)

    def test_traversal_entry_rejected(self):
        content, manifest = self.fixture()
        with self.assertRaisesRegex(ValueError, "flat file set"):
            self.verify(content, manifest, ("../asr.onnx", b"bad"))

    def test_unknown_model_cannot_relabel_itself_latest(self):
        content, manifest = self.fixture()
        manifest["model_id"] = "gigaam-v1"
        with self.assertRaisesRegex(ValueError, "Unsupported model_id"):
            self.verify(content, manifest)

    def test_declared_expanded_size_limited(self):
        content, manifest = self.fixture()
        manifest["files"]["asr.onnx"]["bytes"] = 10_000_000_000
        with self.assertRaisesRegex(ValueError, "Invalid byte count"):
            self.verify(content, manifest)

    def test_duplicate_manifest_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
            load_json('{"schema_version":1,"schema_version":2}')

    def test_ctc_blank_separates_repeated_characters(self):
        # "ааб" requires a blank between the two а emissions.
        text, frames = ctc_decode([0, 0, 3, 0, 0, 1, 1, 3], ["а", "б", " "], 8)
        self.assertEqual(text, "ааб")
        self.assertEqual(frames, [0, 3, 5])

    def test_ctc_respects_valid_length(self):
        text, frames = ctc_decode([0, 3, 1], ["а", "б", " "], 2)
        self.assertEqual((text, frames), ("а", [0]))

    def test_ctc_unknown_token_is_error(self):
        with self.assertRaisesRegex(ValueError, "Token outside vocabulary"):
            ctc_decode([5], ["а", "б", " "], 1)


if __name__ == "__main__":
    unittest.main()
