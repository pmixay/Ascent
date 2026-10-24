import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_android", ROOT / "scripts/prepare_android.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RuntimePreparationTest(unittest.TestCase):
    def test_preserves_jni_and_removes_conflicting_ort_from_all_architectures(self):
        source = io.BytesIO()
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("classes.jar", b"classes")
            archive.writestr("AndroidManifest.xml", b"manifest")
            for arch in ("arm64-v8a", "x86_64"):
                archive.writestr(f"jni/{arch}/libsherpa-onnx-jni.so", b"speaker bindings")
                archive.writestr(f"jni/{arch}/libonnxruntime.so", b"bundled runtime")
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.aar"
            module.repackage(source.getvalue(), output)
            with zipfile.ZipFile(output) as result:
                self.assertEqual(result.read("classes.jar"), b"classes")
                self.assertEqual(result.read("jni/arm64-v8a/libsherpa-onnx-jni.so"), b"speaker bindings")
                self.assertFalse(any(name.endswith("/libonnxruntime.so") for name in result.namelist()))

    def test_changed_upstream_layout_fails_before_output(self):
        source = io.BytesIO()
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("classes.jar", b"incomplete")
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.aar"
            with self.assertRaises(ValueError):
                module.repackage(source.getvalue(), output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
