"""CI-only synthetic receipt/cache regressions; no ML dependencies or inference."""
from copy import deepcopy
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import export_provenance as provenance


class ExportProvenanceTests(unittest.TestCase):
    producer = {"commit": "a" * 40, "run_id": "123", "run_attempt": "1"}
    manifest_sha256 = "b" * 64

    def repository(self, root):
        for name in provenance.INPUTS + (provenance.FIXTURES + "/public.wav",):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic input")

    def test_identical_inputs_on_another_commit_can_reuse_validated_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.repository(root)
            receipt = provenance.describe(self.manifest_sha256, self.producer, root)
            with patch.dict(os.environ, {"GITHUB_SHA": "c" * 40}):
                provenance.verify_receipt(receipt, self.manifest_sha256, root)

    def test_changed_source_dependencies_pins_or_fixtures_reject_warm_receipt(self):
        for name in provenance.INPUTS + (provenance.FIXTURES + "/public.wav",):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.repository(root)
                receipt = provenance.describe(self.manifest_sha256, self.producer, root)
                (root / name).write_bytes(b"different input")
                with self.assertRaisesRegex(ValueError, "inputs differ"):
                    provenance.verify_receipt(receipt, self.manifest_sha256, root)

    def test_missing_and_added_fixture_do_not_match_previous_validation(self):
        for change in ("remove", "add"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.repository(root)
                receipt = provenance.describe(self.manifest_sha256, self.producer, root)
                if change == "remove":
                    (root / provenance.FIXTURES / "public.wav").unlink()
                else:
                    (root / provenance.FIXTURES / "another.wav").write_bytes(b"synthetic")
                with self.assertRaises(ValueError):
                    provenance.verify_receipt(receipt, self.manifest_sha256, root)

    def test_wrong_manifest_fingerprint_schema_or_producer_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.repository(root)
            original = provenance.describe(self.manifest_sha256, self.producer, root)
            for key, value in (("manifest_sha256", "0" * 64), ("inputs_sha256", "0" * 64),
                               ("inputs", {}), ("schema_version", True), ("schema_version", 2),
                               ("producer", {}), ("producer", None)):
                with self.subTest(key=key, value=value):
                    receipt = deepcopy(original)
                    receipt[key] = value
                    with self.assertRaises(ValueError):
                        provenance.verify_receipt(receipt, self.manifest_sha256, root)

    def test_missing_partial_oversized_duplicate_or_non_object_receipt_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "receipt.json"
            for content in (None, "{", "x" * (128 * 1024 + 1), '[]', '{"schema_version":1,"schema_version":1}'):
                with self.subTest(content=None if content is None else content[:30]):
                    if content is not None:
                        path.write_text(content)
                    with self.assertRaises(ValueError):
                        provenance.load_receipt(path, self.manifest_sha256, root)

    def test_receipt_and_cache_key_cover_the_same_source_inputs(self):
        workflow = (provenance.REPOSITORY / ".github/workflows/android.yml").read_text()
        key = next(line for line in workflow.splitlines() if "key:" in line and "offline-export-py312-" in line)
        keyed_paths = set(re.findall(r"'([^']+)'", key))
        self.assertEqual(set(provenance.INPUTS) | {provenance.FIXTURES + "/**"}, keyed_paths)
        offline = workflow.split("  offline-build:", 1)[1]
        self.assertNotIn("restore-keys:", offline)
        self.assertEqual(2, offline.count("prototypes/phone-transcriber/build/offline-export-provenance.json"))
        self.assertLess(offline.index("Reuse required export parity"), offline.index("Record successful acoustic export"))
        self.assertLess(offline.index("Record successful acoustic export"), offline.index("Prepare pinned acoustic"))
        self.assertIn("--validation-receipt build/offline-export-provenance.json", offline)

    def test_record_requires_ci_and_never_overwrites_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}), self.assertRaisesRegex(ValueError, "GitHub CI"):
                provenance.record(root, root / "receipt.json")
            path = root / "receipt.json"
            path.write_text("existing evidence")
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), self.assertRaises(FileExistsError):
                provenance.record(root, path)
            self.assertEqual("existing evidence", path.read_text())

    def test_successful_cold_record_is_reusable_and_failed_validation_creates_no_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.repository(root)
            acoustic = root / "export"
            acoustic.mkdir()
            (acoustic / "manifest.json").write_text("{}")
            output = root / "receipt.json"
            environment = {"GITHUB_ACTIONS": "true", "GITHUB_SHA": self.producer["commit"],
                           "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
            with patch.dict(os.environ, environment), patch.object(provenance, "validate_directory") as validate:
                validate.side_effect = ValueError("Invalid export")
                with self.assertRaisesRegex(ValueError, "Invalid export"):
                    provenance.record(acoustic, output, root)
                self.assertFalse(output.exists())
                validate.side_effect = None
                provenance.record(acoustic, output, root)
                validate.assert_called_with(acoustic)
            receipt = provenance.load_receipt(output, provenance.sha256_file(acoustic / "manifest.json"), root)
            self.assertEqual(self.producer, receipt["producer"])

    def test_packager_checks_receipt_before_any_asset_copy_or_download(self):
        import prepare_embedded_models as prepare
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(prepare, "verify_acoustic"), patch.object(prepare, "sha256_file", return_value=self.manifest_sha256), \
                    patch.object(prepare, "download") as download, patch.object(prepare.shutil, "copyfile") as copy:
                with self.assertRaisesRegex(ValueError, "receipt"):
                    prepare.prepare(root, root / "assets", root / "cache", root / "missing.json")
                copy.assert_not_called()
                download.assert_not_called()
                self.assertFalse((root / "assets").exists())
