"""Reject stale, substituted and partial inference caches before JVM scoring."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from evaluate_dialogs import checkpoint_digest
from model_pack import SPEAKER_SHA256
from reference_asr import REFERENCE
from verify_dialog_cache import ASR_HASHES, verify


class DialogCacheVerificationTests(unittest.TestCase):
    def fixture(self, root):
        manifest = {"dataset": {"repository": "public-fixture", "revision": "frozen"},
                    "selection": {"source_files": 1}, "models": {"manifest_sha256": "deployed-manifest"}}
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest))
        audio = b"fixture-pcm-is-not-decoded-by-this-integrity-check"
        (root / "session.wav").write_bytes(audio)
        metrics = {"words": 1, "word_errors": 0, "chars": 1, "char_errors": 0}
        session = {"id": "session", "schema_version": 1, "cache_fingerprint": "current-pipeline",
                   "audio_file": "session.wav", "audio_sha256": hashlib.sha256(audio).hexdigest(),
                   "duration_ms": 7200000, "source_duration_ms": 7200000, "asr_metrics": metrics,
                   "references": [{"source_id": "frozen.wav", "start_ms": 0, "end_ms": 7200000}]}
        session["checkpoint_sha256"] = checkpoint_digest(session)
        index = {"schema_version": 1, "source_manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                 "cache_fingerprint": "current-pipeline", "sessions": [{"id": "session", "file": "session.json"}],
                 "source_files": 1, "source_duration_ms": 7200000, "asr_metrics": metrics,
                 "provenance": {"asr_source": "public-reference", "asr_sha256": ASR_HASHES["public-reference"],
                                "speaker_sha256": SPEAKER_SHA256, "repository": "public-fixture", "revision": "frozen",
                                "model_manifest_sha256": "public-reference",
                                "asr_reference": {"repository": REFERENCE["repository"],
                                                  "revision": REFERENCE["revision"], "variant": "float32"}}}
        return manifest_path, session, index

    def check(self, mutate=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path, session, index = self.fixture(root)
            if mutate:
                mutate(root, session, index)
            (root / "session.json").write_text(json.dumps(session))
            (root / "evaluation.json").write_text(json.dumps(index))
            with patch("verify_dialog_cache.MANIFEST", manifest_path), \
                    patch("verify_dialog_cache.validate_selection"), \
                    patch("verify_dialog_cache.fingerprint", return_value="current-pipeline"), \
                    patch("verify_dialog_cache.blocks", return_value=[("session", [{"path": "frozen.wav"}])]):
                return verify(root)

    def test_complete_cache_returns_content_free_provenance(self):
        result = self.check()
        self.assertEqual(result["asr_source"], "public-reference")
        self.assertEqual(result["source_duration_ms"], 7200000)

    def test_rejects_substituted_model_and_stale_pipeline(self):
        for key, value in (("asr_source", "other"), ("asr_sha256", "wrong"), ("speaker_sha256", "wrong")):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "provenance"):
                self.check(lambda root, session, index: index["provenance"].update({key: value}))
        with self.assertRaisesRegex(ValueError, "pipeline fingerprint"):
            self.check(lambda root, session, index: index.update(cache_fingerprint="stale"))

    def test_accepts_only_the_separately_pinned_deployed_provenance(self):
        def deployed(root, session, index):
            index["provenance"].update(asr_source="deployed", asr_sha256=ASR_HASHES["deployed"],
                                       model_manifest_sha256="deployed-manifest")
            index["provenance"].pop("asr_reference")
        self.assertEqual(self.check(deployed)["asr_source"], "deployed")

    def test_rejects_changed_source_manifest_or_reference_revision(self):
        with self.assertRaisesRegex(ValueError, "source manifest"):
            self.check(lambda root, session, index: index.update(source_manifest_sha256="other"))
        with self.assertRaisesRegex(ValueError, "reference export revision"):
            self.check(lambda root, session, index: index["provenance"]["asr_reference"].update(revision="main"))

    def test_rejects_changed_session_fingerprint_even_with_valid_checksum(self):
        def mutate(root, session, index):
            session["cache_fingerprint"] = "old-pipeline"
            session["checkpoint_sha256"] = checkpoint_digest(session)
        with self.assertRaisesRegex(ValueError, "session integrity/fingerprint"):
            self.check(mutate)

    def test_rejects_incomplete_index(self):
        with self.assertRaisesRegex(ValueError, "sessions incomplete"):
            self.check(lambda root, session, index: index.update(sessions=[]))

    def test_rejects_modified_predictions_and_audio(self):
        with self.assertRaisesRegex(ValueError, "session integrity"):
            self.check(lambda root, session, index: session.update(words=[{"text": "changed"}]))
        with self.assertRaisesRegex(ValueError, "audio integrity"):
            self.check(lambda root, session, index: (root / "session.wav").write_bytes(b"changed"))

    def test_rejects_redirected_audio_even_with_recomputed_checkpoint(self):
        def mutate(root, session, index):
            session["audio_file"] = "../outside.wav"
            session["checkpoint_sha256"] = checkpoint_digest(session)
        with self.assertRaisesRegex(ValueError, "audio path"):
            self.check(mutate)

    def test_rejects_inflated_source_accounting(self):
        with self.assertRaisesRegex(ValueError, "aggregate accounting"):
            self.check(lambda root, session, index: index.update(source_files=2))


if __name__ == "__main__":
    unittest.main()
