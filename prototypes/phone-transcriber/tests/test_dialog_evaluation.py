"""Integrity and accounting checks; actual speech inference runs in the CI evaluation job."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from evaluate_dialogs import MANIFEST, blocks, checkpoint_digest, counts, fetch, validate_selection
from evaluate_speech import edit_distance


class DialogEvaluationTest(unittest.TestCase):
    def selection(self):
        return json.loads(MANIFEST.read_text())

    def test_frozen_selection_contains_two_hours_of_unique_labeled_payloads(self):
        manifest = self.selection()
        validate_selection(manifest)
        self.assertEqual(1134, manifest["selection"]["source_files"])
        self.assertGreaterEqual(manifest["selection"]["source_seconds"], 7200)
        self.assertEqual(["D", "M", "S"], manifest["selection"]["speakers"])
        self.assertEqual("bb11205cc6ce6b28c601aa09173eba2e5b88d5e94394f2f4bfb27834a4c93c73",
                         hashlib.sha256(MANIFEST.read_bytes()).hexdigest())

    def test_repeated_audio_cannot_inflate_two_hour_denominator(self):
        manifest = self.selection()
        files = manifest["recordings"][0]["files"]
        files[1]["sha256"] = files[0]["sha256"]
        with self.assertRaisesRegex(ValueError, "unique"):
            validate_selection(manifest)

    def test_source_paths_cannot_escape_the_frozen_corpus(self):
        manifest = self.selection()
        manifest["recordings"][0]["files"][0]["path"] = "../../private.wav"
        with self.assertRaises(ValueError):
            validate_selection(manifest)

    def test_blocking_preserves_source_order_without_repeating_or_splitting_utterances(self):
        manifest = self.selection()
        expected = [item["path"] for recording in manifest["recordings"] for item in recording["files"]]
        result = list(blocks(manifest))
        actual = [item["path"] for _, items in result for item in items]
        self.assertEqual(expected, actual)
        self.assertEqual(len(actual), len(set(actual)))
        self.assertEqual(len(result), len({identifier for identifier, _ in result}))
        self.assertTrue(all(0 < sum(item["duration_seconds"] for item in items) <= 300 for _, items in result))

    def test_asr_error_counts_keep_deletions_and_empty_output(self):
        measured = counts("Ёжик идёт домой.", "ежик домой", edit_distance)
        self.assertEqual(3, measured["words"])
        self.assertEqual(1, measured["word_errors"])
        empty = counts("два слова", "", edit_distance)
        self.assertEqual(empty["words"], empty["word_errors"])
        self.assertEqual(empty["chars"], empty["char_errors"])

    def test_generated_checkpoint_checksum_covers_predictions_and_references(self):
        record = {"id": "public-session", "words": [{"text": "слово"}], "references": [{"speaker": "M"}]}
        record["checkpoint_sha256"] = checkpoint_digest(record)
        self.assertEqual(record["checkpoint_sha256"], checkpoint_digest(record))
        record["references"][0]["speaker"] = "D"
        self.assertNotEqual(record["checkpoint_sha256"], checkpoint_digest(record))

    def test_verified_cache_is_used_without_network(self):
        data = b"public source fixture"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "sample.wav"
            target.write_bytes(data)
            with patch("urllib.request.urlopen") as connection:
                self.assertEqual(target, fetch("https://example.invalid/public.wav", target,
                                               len(data), hashlib.sha256(data).hexdigest()))
                connection.assert_not_called()

    def test_download_rejects_oversize_content_and_never_promotes_it(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "sample.wav"
            with patch("urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"too long")), \
                    patch("time.sleep"):
                with self.assertRaisesRegex(ValueError, "exceeds"):
                    fetch("https://example.invalid/public.wav", target, 3, hashlib.sha256(b"abc").hexdigest())
            self.assertFalse(target.exists())
            self.assertFalse(target.with_suffix(".wav.part").exists())

    def test_download_rejects_wrong_hash_even_when_size_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "sample.wav"
            with patch("urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"bad")), \
                    patch("time.sleep"):
                with self.assertRaisesRegex(ValueError, "integrity"):
                    fetch("https://example.invalid/public.wav", target, 3, hashlib.sha256(b"abc").hexdigest())
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
