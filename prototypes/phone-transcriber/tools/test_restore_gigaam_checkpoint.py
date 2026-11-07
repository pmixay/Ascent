"""Same-checkpoint mirror integrity cases; executed only in existing GitHub CI."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import restore_gigaam_checkpoint as checkpoint


class RestoreGigaamCheckpointTests(unittest.TestCase):
    def pins(self, data: bytes):
        return {"ASCENT_GIGAAM_CHECKPOINT_URL": "https://private.example.invalid/multilingual_ctc.ckpt",
                "ASCENT_GIGAAM_CHECKPOINT_SHA256": hashlib.sha256(data).hexdigest(),
                "ASCENT_GIGAAM_CHECKPOINT_BYTES": str(len(data))}

    def test_mirror_without_all_independent_pins_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            for setting in self.pins(b"checkpoint"):
                with self.subTest(setting=setting), patch.dict(os.environ, {setting: "present"}, clear=True):
                    with self.assertRaisesRegex(ValueError, "complete verified"):
                        checkpoint.restore(Path(temporary))

    def test_matching_mirror_is_promoted_only_after_the_original_checksum_matches(self):
        data = b"unchanged official checkpoint fixture"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def download(source, target, size, digest, *, maximum_size):
                self.assertFalse((directory / checkpoint.FILENAME).exists())
                target.write_bytes(data)
            with patch.dict(os.environ, self.pins(data), clear=True), \
                    patch.object(checkpoint, "UPSTREAM_CHECKPOINT_MD5", hashlib.md5(data).hexdigest()), \
                    patch.object(checkpoint, "download", side_effect=download):
                checkpoint.restore(directory)
            self.assertEqual(data, (directory / checkpoint.FILENAME).read_bytes())
            self.assertEqual([checkpoint.FILENAME], [path.name for path in directory.iterdir()])

    def test_mirror_sha256_cannot_replace_the_original_model_checksum(self):
        data = b"different model even though the mirror pins match"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def download(source, target, size, digest, *, maximum_size):
                target.write_bytes(data)
            with patch.dict(os.environ, self.pins(data), clear=True), \
                    patch.object(checkpoint, "download", side_effect=download):
                with self.assertRaisesRegex(ValueError, "fixed official"):
                    checkpoint.restore(directory)
            self.assertFalse((directory / checkpoint.FILENAME).exists())
            self.assertEqual([], list(directory.iterdir()))

    def test_corrupt_restored_cache_is_rejected_without_fetching_a_substitute(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / checkpoint.FILENAME).write_bytes(b"corrupt")
            with patch.dict(os.environ, {}, clear=True), patch.object(checkpoint, "download") as download:
                with self.assertRaisesRegex(ValueError, "fixed official"):
                    checkpoint.restore(directory)
                download.assert_not_called()

    def test_cached_original_still_needs_the_independent_mirror_sha256_when_configured(self):
        data = b"official checkpoint fixture"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / checkpoint.FILENAME).write_bytes(data)
            pins = self.pins(data)
            pins["ASCENT_GIGAAM_CHECKPOINT_SHA256"] = "0" * 64
            with patch.dict(os.environ, pins, clear=True), \
                    patch.object(checkpoint, "UPSTREAM_CHECKPOINT_MD5", hashlib.md5(data).hexdigest()):
                with self.assertRaisesRegex(ValueError, "pinned mirror SHA-256"):
                    checkpoint.restore(directory)

    def test_unconfigured_mirror_leaves_the_existing_official_https_export_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.dict(os.environ, {}, clear=True), patch.object(checkpoint, "download") as download:
                checkpoint.restore(directory)
                download.assert_not_called()
            self.assertEqual([], list(directory.iterdir()))


if __name__ == "__main__":
    unittest.main()
