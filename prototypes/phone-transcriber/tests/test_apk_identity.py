"""CI-only synthetic archive inspection; no Android build, SDK or model weights."""
from contextlib import redirect_stdout
import hashlib
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from check_apk import inspect


class ApkIdentityTests(unittest.TestCase):
    def fixture(self, root, abi="arm64-v8a", with_weight=False):
        apk = root / "synthetic.apk"
        mapping = root / "mapping.txt"
        mapping.write_text("# compiler: R8\nexample.App -> a:\n")
        dex = bytearray(112)
        dex[:8] = b"dex\n035\0"
        struct.pack_into("<I", dex, 96, 1)
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", dex)
            for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so", "libort_sherpa.so", "libsherpa-onnx-jni.so"):
                archive.writestr(f"lib/{abi}/{name}", b"synthetic native fixture")
            if with_weight:
                archive.writestr("assets/forbidden.onnx", b"synthetic forbidden payload")
        return apk, mapping

    def test_prints_raw_checked_apk_hash_and_size_not_an_artifact_zip_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, mapping = self.fixture(Path(directory))
            expected = hashlib.sha256(apk.read_bytes()).hexdigest()
            output = io.StringIO()
            with redirect_stdout(output):
                inspect(apk, mapping)
            self.assertIn(f"APK SHA-256: {expected}", output.getvalue())
            self.assertIn(f"Phone APK: {apk.stat().st_size:,} bytes", output.getvalue())

    def test_failed_native_or_weight_gate_never_emits_a_verified_apk_identity(self):
        for flags in ({"abi": "x86_64"}, {"with_weight": True}):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory:
                apk, mapping = self.fixture(Path(directory), **flags)
                output = io.StringIO()
                with redirect_stdout(output), self.assertRaises(ValueError):
                    inspect(apk, mapping)
                self.assertNotIn("APK SHA-256:", output.getvalue())
