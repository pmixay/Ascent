"""Transport/config regression checks. Run in the existing GitHub CI jobs only."""
import copy
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
import urllib.request

from checkpoint_source import HttpsOnlyRedirect, fetch_file, upstream_config, upstream_state


class Response(io.BytesIO):
    def geturl(self):
        return "https://cdn.example.invalid/model"


class CheckpointSourceTests(unittest.TestCase):
    def download(self, directory, received=b"weights"):
        data = b"weights"
        records = {"pytorch_model.bin": {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}}
        opener = Mock()
        opener.open.side_effect = lambda *args, **kwargs: Response(received)
        with patch("checkpoint_source.FILES", records), \
                patch("checkpoint_source.urllib.request.build_opener", return_value=opener), \
                patch("checkpoint_source.time.sleep"):
            target = fetch_file("pytorch_model.bin", directory)
        return target, opener

    def test_cold_download_then_verified_warm_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target, opener = self.download(root)
            self.assertEqual(target.read_bytes(), b"weights")
            opener.open.assert_called_once()
            _, opener = self.download(root)
            opener.open.assert_not_called()

    def test_corrupt_cached_file_is_recovered(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pytorch_model.bin").write_bytes(b"corrupt")
            target, opener = self.download(root)
            self.assertEqual(target.read_bytes(), b"weights")
            opener.open.assert_called_once()

    def test_wrong_hash_truncation_and_oversize_never_activate(self):
        for payload in (b"corrupt", b"short", b"too many bytes"):
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaises(ValueError):
                    self.download(root, payload)
                self.assertEqual(list(root.iterdir()), [])

    def test_abandoned_staging_is_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pytorch_model.bin.part").write_bytes(b"interrupted")
            target, _ = self.download(root)
            self.assertEqual(target.read_bytes(), b"weights")
            self.assertFalse((root / "pytorch_model.bin.part").exists())

    def test_tls_error_does_not_promote_partial_file(self):
        import ssl
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            opener = Mock()
            opener.open.side_effect = ssl.SSLCertVerificationError("expired")
            with patch("checkpoint_source.urllib.request.build_opener", return_value=opener), \
                    patch("checkpoint_source.time.sleep"), self.assertRaises(ssl.SSLCertVerificationError):
                fetch_file("config.json", root)
            self.assertEqual(list(root.iterdir()), [])

    def test_redirect_cannot_downgrade_tls(self):
        request = urllib.request.Request("https://huggingface.co/model")
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            HttpsOnlyRedirect().redirect_request(request, None, 302, "Found", {}, "http://example.invalid/model")

    def config(self):
        return {"cfg": {"model": {"_target_": "modeling_gigaam.GigaAMASR", "cfg": {
            "model_name": "multilingual_ctc", "model_class": "ctc", "sample_rate": 16000,
            "preprocessor": {"_target_": "modeling_gigaam.FeatureExtractor", "center": False},
            "encoder": {"_target_": "modeling_gigaam.ConformerEncoder", "n_layers": 16},
            "head": {"_target_": "modeling_gigaam.CTCHead", "num_classes": 71},
            "decoding": {"_target_": "modeling_gigaam.CTCGreedyDecoding", "vocabulary": [" ", "а"]},
        }}}}

    def test_mapping_preserves_numerical_settings_and_input_document(self):
        original = self.config()
        untouched = copy.deepcopy(original)
        mapped = upstream_config(original)
        self.assertEqual(original, untouched)
        for section in ("preprocessor", "encoder", "head", "decoding"):
            self.assertTrue(mapped[section]["_target_"].startswith("gigaam."))
            mapped[section]["_target_"] = original["cfg"]["model"]["cfg"][section]["_target_"]
        self.assertEqual(mapped, original["cfg"]["model"]["cfg"])

    def test_unreviewed_class_is_rejected(self):
        document = self.config()
        document["cfg"]["model"]["cfg"]["encoder"]["_target_"] = "os.system"
        with self.assertRaisesRegex(ValueError, "Unsupported checkpoint component"):
            upstream_config(document)

    def test_state_mapping_does_not_drop_unknown_keys_or_transform_values(self):
        value = object()
        self.assertIs(upstream_state({"model.encoder.weight": value})["encoder.weight"], value)
        for state in ({}, {"encoder.weight": value}, {"model.extra.weight": value}):
            with self.assertRaisesRegex(ValueError, "dictionary keys"):
                upstream_state(state)


if __name__ == "__main__":
    unittest.main()
