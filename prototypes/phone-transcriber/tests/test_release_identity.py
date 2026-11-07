"""CI-only synthetic signing/provenance tests; no key generation, SDK or inference."""
import base64
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ci_signing
import collect_ci_evidence as collect
import record_apk_identity as apk_identity


class SigningTests(unittest.TestCase):
    def environment(self, root, protected=False):
        env = {"GITHUB_ACTIONS": "true", "GITHUB_ENV": str(root / "github-env"), "RUNNER_TEMP": str(root)}
        if protected:
            env.update(dict(zip(ci_signing.SECRET_NAMES, (
                base64.b64encode(b"synthetic-not-a-real-key").decode(), "store-password", "test-alias", "key-password"))))
            env["ASCENT_SIGNING_CERT_SHA256"] = "aa:" * 31 + "aa"
        return env

    def test_absent_key_is_explicitly_internal_not_a_generated_release_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = self.environment(root)
            self.assertEqual("unprotected-debug", ci_signing.prepare(env))
            self.assertEqual("ASCENT_SIGNING_MODE=unprotected-debug\n", Path(env["GITHUB_ENV"]).read_text())
            self.assertEqual(["github-env"], [path.name for path in root.iterdir()])

    def test_required_missing_partial_invalid_or_oversized_key_fails_without_a_downgrade(self):
        for mutation in ("required", "partial", "certificate", "base64", "oversized", "not-ci"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                env = self.environment(root, protected=True)
                if mutation == "required":
                    env = self.environment(root)
                    env["ASCENT_REQUIRE_STABLE_SIGNING"] = "true"
                if mutation == "partial": del env[ci_signing.SECRET_NAMES[-1]]
                if mutation == "certificate": env["ASCENT_SIGNING_CERT_SHA256"] = "wrong"
                if mutation == "base64": env[ci_signing.SECRET_NAMES[0]] = "not base64!"
                if mutation == "oversized": env[ci_signing.SECRET_NAMES[0]] = "x" * (2 * 1024 * 1024 + 1)
                if mutation == "not-ci": env["GITHUB_ACTIONS"] = "false"
                with self.assertRaises(ValueError): ci_signing.prepare(env)
                self.assertEqual([], list(root.iterdir()))

    def test_owner_key_has_private_files_no_passwords_in_environment_and_bounded_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = self.environment(root, protected=True)
            self.assertEqual("protected-key", ci_signing.prepare(env))
            exported = Path(env["GITHUB_ENV"]).read_text()
            for secret in ("store-password", "key-password", "test-alias", env[ci_signing.SECRET_NAMES[0]]):
                self.assertNotIn(secret, exported)
            values = dict(line.split("=", 1) for line in exported.splitlines())
            path = Path(values["ASCENT_SIGNING_PROPERTIES"])
            self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            key = path.parent / "identity.keystore"
            self.assertEqual(0o600, stat.S_IMODE(key.stat().st_mode))
            self.assertEqual(b"synthetic-not-a-real-key", key.read_bytes())
            unrelated = root / "user-owned.txt"
            unrelated.write_text("keep")
            ci_signing.cleanup(env | values)
            self.assertFalse(path.parent.exists())
            self.assertEqual("keep", unrelated.read_text())

    def test_cleanup_refuses_an_unrelated_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "signing.properties"
            path.write_text("keep")
            with self.assertRaises(ValueError):
                ci_signing.cleanup({"RUNNER_TEMP": directory, "ASCENT_SIGNING_PROPERTIES": str(path)})
            self.assertEqual("keep", path.read_text())

    def test_properties_escape_passwords_paths_and_non_bmp_characters(self):
        escaped = ci_signing.properties_value(" a\\b=\nЖ😀")
        self.assertTrue(escaped.isascii())
        self.assertNotIn("\n", escaped)
        self.assertEqual(" a\\b=\nЖ😀", bytes.fromhex(escaped.replace("\\u", "")).decode("utf-16-be"))


class IdentityTests(unittest.TestCase):
    def fixture(self, root, kind="compact"):
        apk = root / "synthetic.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"synthetic, not executable")
            if kind == "offline":
                archive.writestr("assets/models/bundle.json", json.dumps({
                    "source_pin_sha256": "b" * 64, "files": {"text-model/model.onnx": {"bytes": 3, "sha256": "c" * 64}},
                    "export_validation": {"producer": {"commit": "d" * 40}}}))
        env = {"GITHUB_ACTIONS": "true", "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123",
               "GITHUB_RUN_ATTEMPT": "1", "GITHUB_RUN_NUMBER": "140", "GITHUB_REPOSITORY": "owner/repo",
               "ASCENT_SIGNING_MODE": "unprotected-debug"}
        suffix = ".offline" if kind == "offline" else ""
        version_suffix = "-offline-baseline" if kind == "offline" else ""
        badging = f"package: name='com.pmixay.ascent{suffix}' versionCode='140' versionName='0.1.140-aaaaaaaa{version_suffix}'\nnative-code: 'arm64-v8a'\n"
        signing = "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + "e" * 64 + "\n"
        return apk, env, badging, signing

    def test_exact_versions_certificate_raw_apk_and_bundle_are_independent(self):
        for kind in ("compact", "offline"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                apk, env, badging, signing = self.fixture(Path(directory), kind)
                result = apk_identity.identity(apk, kind, badging, signing, env)
                self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), result["apk_sha256"])
                self.assertEqual("e" * 64, result["certificate_sha256"])
                self.assertEqual(140, result["version_code"])
                self.assertIs(False, result["release_accepted"])
                if kind == "offline":
                    self.assertNotEqual(result["apk_sha256"], result["bundle_sha256"])
                    self.assertEqual(3, result["expanded_payload_bytes"])

    def test_wrong_revision_version_abi_debuggability_or_signature_never_qualifies(self):
        for mutation in ("version", "revision", "abi", "debuggable", "missing-cert", "two-signers", "not-ci", "required-key"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                apk, env, badging, signing = self.fixture(Path(directory))
                if mutation == "version": badging = badging.replace("versionCode='140'", "versionCode='1'")
                if mutation == "revision": badging = badging.replace("aaaaaaaa", "bbbbbbbb")
                if mutation == "abi": badging = badging.replace("arm64-v8a", "x86_64")
                if mutation == "debuggable": badging += "application-debuggable\n"
                if mutation == "missing-cert": signing = ""
                if mutation == "two-signers": signing += signing.replace("#1", "#2")
                if mutation == "not-ci": env["GITHUB_ACTIONS"] = "false"
                if mutation == "required-key": env["ASCENT_REQUIRE_STABLE_SIGNING"] = "true"
                with self.assertRaises(ValueError): apk_identity.identity(apk, "compact", badging, signing, env)

    def test_protected_signing_requires_the_actual_pinned_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, env, badging, signing = self.fixture(Path(directory))
            env.update(ASCENT_SIGNING_MODE="protected-key", ASCENT_EXPECTED_CERT_SHA256="f" * 64)
            with self.assertRaises(ValueError): apk_identity.identity(apk, "compact", badging, signing, env)
            env["ASCENT_EXPECTED_CERT_SHA256"] = "e" * 64
            self.assertEqual("protected-key", apk_identity.identity(apk, "compact", badging, signing, env)["signing_mode"])

    def test_sdk_range_signer_output_preserves_one_identity_and_the_protected_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, env, badging, _ = self.fixture(Path(directory))
            signing = ("Verifies\nVerified using v3.1 scheme (APK Signature Scheme v3.1): true\n"
                       "Number of signers: 1\n"
                       "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: " + "e" * 64 + "\n"
                       "Signer (minSdkVersion=31, maxSdkVersion=32) certificate SHA-256 digest: " + "e" * 64 + "\n"
                       "Source Stamp Signer certificate SHA-256 digest: " + "f" * 64 + "\n")
            env.update(ASCENT_SIGNING_MODE="protected-key", ASCENT_EXPECTED_CERT_SHA256="e" * 64)
            result = apk_identity.identity(apk, "compact", badging, signing, env)
            self.assertEqual("e" * 64, result["certificate_sha256"])
            for invalid in (
                    signing.replace("Number of signers: 1", "Number of signers: 2"),
                    signing.replace("Number of signers: 1\n", ""),
                    signing.replace("maxSdkVersion=32) certificate SHA-256 digest: " + "e" * 64,
                                    "maxSdkVersion=32) certificate SHA-256 digest: " + "f" * 64),
                    signing.replace("Signer (minSdkVersion=31, maxSdkVersion=32)", "Signer unknown"),
                    signing.replace("Signer (minSdkVersion=31, maxSdkVersion=32)", "Signer #1")):
                with self.subTest(signing=invalid), self.assertRaises(ValueError):
                    apk_identity.identity(apk, "compact", badging, invalid, env)
            env["ASCENT_EXPECTED_CERT_SHA256"] = "a" * 64
            with self.assertRaises(ValueError):
                apk_identity.identity(apk, "compact", badging, signing, env)

    def test_sdk_verification_failure_prints_no_identity_or_stderr(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, env, _, _ = self.fixture(Path(directory))
            output = io.StringIO()
            with patch.object(apk_identity.subprocess, "run", return_value=Mock(returncode=1, stderr="private DN")), redirect_stdout(output):
                with self.assertRaises(ValueError): apk_identity.record(apk, "compact", Path(directory), env)
            self.assertEqual("", output.getvalue())

    def test_signer_format_diagnostic_excludes_certificate_subjects(self):
        signing = ("Verifies\nNumber of signers: 1\n"
                   "Signer unknown certificate DN: CN=private-subject, OU=private-team\n"
                   "Signer unknown certificate SHA-256 digest: " + "e" * 64 + "\n")
        with self.assertRaises(apk_identity.IdentityError) as failure:
            apk_identity.signer_certificate(signing)
        message = str(failure.exception)
        self.assertIn('"signer_counts":["1"]', message)
        self.assertIn('"certificate_labels":["Signer unknown"]', message)
        self.assertNotIn("private-subject", message)
        self.assertNotIn("private-team", message)
        self.assertNotIn(apk_identity.PREFIX, message)


class CollectorTests(unittest.TestCase):
    def fixture(self):
        sha = "a" * 40
        run = {"id": 123, "run_attempt": 1, "head_sha": sha, "event": "push", "status": "completed",
               "conclusion": "success", "html_url": "https://github.com/owner/repo/actions/runs/123"}
        jobs = [{"id": index, "name": name, "status": "completed", "conclusion": "success"}
                for index, name in enumerate(collect.JOBS, 1)]
        artifacts, logs = [], {}
        for job in jobs:
            kind, count = collect.JOBS[job["name"]]
            record = {"schema_version": 1, "kind": kind, "source_commit": sha, "repository": "owner/repo",
                      "run_id": "123", "run_attempt": "1", "abi": "arm64-v8a", "release_accepted": False,
                      "apk_bytes": 100, "apk_sha256": "b" * 64, "certificate_sha256": "c" * 64,
                      "signing_mode": "unprotected-debug", "bundle_sha256": "d" * 64}
            logs[job["id"]] = ("timestamp " + apk_identity.PREFIX + json.dumps(record) + "\n" +
                               f"Verified instrumentation XML: {count} distinct executed cases; {count} required cases passed without skips.\n")
            if kind == "offline":
                sample = {"source_commit": sha, "bundle_sha256": "d" * 64, "apk_abi": "x86_64"}
                logs[job["id"]] += ("AscentModelStorage: " + json.dumps(sample) + "\n") * 2
            artifacts.append({"id": job["id"], "name": ("debug-" if kind == "compact" else "offline-baseline-") + sha[:8],
                              "size_in_bytes": 200, "digest": "sha256:" + "e" * 64, "expired": False,
                              "expires_at": "2026-11-14T00:00:00Z", "workflow_run": {"head_sha": sha}})
        client = Mock()
        client.get.side_effect = [{"workflow_runs": [run]}, {"jobs": jobs}, {"artifacts": artifacts}]
        client.logs.side_effect = lambda job_id: logs[job_id]
        return client, sha, run, jobs, artifacts, logs

    def test_complete_exact_run_preserves_zip_vs_apk_identity_and_no_release_acceptance(self):
        client, sha, _, _, _, _ = self.fixture()
        result = collect.inspect(client, "owner/repo", sha)
        self.assertIs(False, result["release_accepted"])
        offline = result["candidates"]["offline"]
        self.assertEqual(100, offline["apk_bytes"])
        self.assertEqual(200, offline["artifact"]["size_in_bytes"])
        self.assertNotEqual(offline["apk_sha256"], offline["artifact"]["digest"])
        self.assertEqual(2, len(offline["x86_64_storage_observations"]))

    def test_missing_failed_stale_skipped_duplicate_or_expired_evidence_fails_closed(self):
        for mutation in ("failed-run", "wrong-sha", "missing-job", "skipped-job", "missing-identity",
                         "duplicate-identity", "wrong-identity", "missing-execution", "expired", "wrong-artifact", "wrong-storage"):
            with self.subTest(mutation=mutation):
                client, sha, run, jobs, artifacts, logs = self.fixture()
                if mutation == "failed-run": run["conclusion"] = "failure"
                if mutation == "wrong-sha": run["head_sha"] = "f" * 40
                if mutation == "missing-job": jobs.pop()
                if mutation == "skipped-job": jobs[0]["conclusion"] = "skipped"
                if mutation == "missing-identity": logs[1] = ""
                if mutation == "duplicate-identity": logs[1] += logs[1]
                if mutation == "wrong-identity": logs[1] = logs[1].replace(sha, "f" * 40)
                if mutation == "missing-execution": logs[1] = logs[1].replace("without skips", "skipped")
                if mutation == "expired": artifacts[0]["expired"] = True
                if mutation == "wrong-artifact": artifacts[0]["workflow_run"]["head_sha"] = "f" * 40
                if mutation == "wrong-storage": logs[2] = logs[2].replace('"apk_abi": "x86_64"', '"apk_abi": "arm64-v8a"')
                with self.assertRaises(ValueError): collect.inspect(client, "owner/repo", sha)

    def test_signed_log_redirect_does_not_receive_the_github_credential(self):
        client = collect.GitHub("owner/repo", "synthetic-private-token")
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b"bounded log"
        client.opener = Mock()
        client.opener.open.side_effect = [urllib.error.HTTPError("https://api.github.com/", 302, "redirect",
                                                               {"Location": "https://logs.example.invalid/signed"}, None), response]
        self.assertEqual("bounded log", client.logs(1))
        first, second = [call.args[0] for call in client.opener.open.call_args_list]
        self.assertIsNotNone(first.get_header("Authorization"))
        self.assertIsNone(second.get_header("Authorization"))

    def test_workflow_preserves_bounded_gates_and_never_uploads_signing_material_or_reports(self):
        workflow = (ROOT.parents[1] / ".github/workflows/android.yml").read_text()
        self.assertEqual(2, workflow.count("python3 scripts/ci_signing.py prepare"))
        self.assertEqual(2, workflow.count("python3 scripts/ci_signing.py cleanup"))
        self.assertEqual(2, workflow.count("python3 ../scripts/record_apk_identity.py --apk"))
        self.assertEqual(2, workflow.count("uses: actions/upload-artifact@"))
        self.assertIn("github.ref_protected && secrets.ASCENT_SIGNING_KEYSTORE_BASE64", workflow)
        self.assertEqual(2, workflow.count("environment: ${{ github.ref_protected && 'android-signing' || 'android-baseline' }}"))
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("dialog-inference:", workflow)
