import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_android", ROOT / "scripts/prepare_android.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def elf_library(bits, soname, needed=("libc.so",)):
    """A small sectionless ELF fixture with real load/dynamic/string-table offsets."""
    is64 = bits == 64
    data = bytearray(1024)
    data[:16] = b"\x7fELF" + bytes((2 if is64 else 1, 1, 1)) + bytes(9)
    header_size, ph_size = (64, 56) if is64 else (52, 32)
    header_format = "<HHIQQQIHHHHHH" if is64 else "<HHIIIIIHHHHHH"
    struct.pack_into(header_format, data, 16, 3, 62 if is64 else 3, 1, 0,
                     header_size, 0, 0, header_size, ph_size, 2, 0, 0, 0)
    strings = bytearray(b"\0")
    references = []
    for tag, value in [(1, value) for value in needed] + [(14, soname)]:
        references.append((tag, len(strings)))
        strings.extend(value.encode() + b"\0")
    entries = [(5, 0x1200), (10, len(strings)), *references, (0, 0)]
    dynamic_size = len(entries) * (16 if is64 else 8)
    if is64:
        struct.pack_into("<IIQQQQQQ", data, header_size, 1, 5, 0, 0x1000, 0, len(data), len(data), 0x1000)
        struct.pack_into("<IIQQQQQQ", data, header_size + ph_size, 2, 6, 0x100, 0x1100, 0,
                         dynamic_size, dynamic_size, 8)
    else:
        struct.pack_into("<IIIIIIII", data, header_size, 1, 0, 0x1000, 0, len(data), len(data), 5, 0x1000)
        struct.pack_into("<IIIIIIII", data, header_size + ph_size, 2, 0x100, 0x1100, 0,
                         dynamic_size, dynamic_size, 6, 4)
    for index, (tag, value) in enumerate(entries):
        struct.pack_into("<qQ" if is64 else "<iI", data, 0x100 + index * (16 if is64 else 8), tag, value)
    data[0x200:0x200 + len(strings)] = strings
    # An unrelated equal string must not be modified by a broad byte replacement.
    marker = b"unrelated libonnxruntime.so\0"
    data[900:900 + len(marker)] = marker
    return bytes(data)


def source_archive(corrupt_name=None, jni_needs_wrapper=False):
    source = io.BytesIO()
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("classes.jar", b"classes")
        archive.writestr("AndroidManifest.xml", b"manifest")
        for arch in module.ARCHITECTURES:
            bits = 64 if arch in ("arm64-v8a", "x86_64") else 32
            for name in (module.ORIGINAL_RUNTIME, *module.SHERPA_LIBRARIES, *module.UNUSED_SHERPA_LIBRARIES):
                needed = ("libc.so",) if name == module.ORIGINAL_RUNTIME else ("libc.so", module.ORIGINAL_RUNTIME)
                if jni_needs_wrapper and name in module.SHERPA_LIBRARIES:
                    needed += (module.UNUSED_SHERPA_LIBRARIES[0],)
                payload = elf_library(bits, name, needed)
                archive.writestr(f"jni/{arch}/{name}", b"broken" if name == corrupt_name else payload)
    return source.getvalue()


class RuntimePreparationTest(unittest.TestCase):
    def test_preserves_each_runtime_and_redirects_every_sherpa_consumer(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.aar"
            module.repackage(source_archive(), output)
            with zipfile.ZipFile(output) as result:
                self.assertEqual(result.read("classes.jar"), b"classes")
                self.assertEqual(result.read("AndroidManifest.xml"), b"manifest")
                self.assertFalse(any(name.endswith("/libonnxruntime.so") for name in result.namelist()))
                for arch in module.ARCHITECTURES:
                    for name in module.UNUSED_SHERPA_LIBRARIES:
                        self.assertNotIn(f"jni/{arch}/{name}", result.namelist())
                    runtime = result.read(f"jni/{arch}/{module.ISOLATED_RUNTIME}")
                    self.assertIn((14, module.ISOLATED_RUNTIME.encode()),
                                  [(tag, text) for tag, _, text in module.dynamic_strings(runtime)])
                    for name in module.SHERPA_LIBRARIES:
                        consumer = result.read(f"jni/{arch}/{name}")
                        self.assertIn((1, module.ISOLATED_RUNTIME.encode()),
                                      [(tag, text) for tag, _, text in module.dynamic_strings(consumer)])

    def test_does_not_drop_a_wrapper_required_by_the_jni_library(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.aar"
            with self.assertRaisesRegex(ValueError, "requires an omitted"):
                module.repackage(source_archive(jni_needs_wrapper=True), output)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_suffix(".tmp").exists())

    def test_rewrite_preserves_offsets_and_all_unrelated_bytes_for_elf32_and_elf64(self):
        for bits in (32, 64):
            with self.subTest(bits=bits):
                source = elf_library(bits, "libconsumer.so", ("libc.so", module.ORIGINAL_RUNTIME))
                expected = bytearray(source)
                offset = source.index(module.ORIGINAL_RUNTIME.encode(), 0x200)
                expected[offset:offset + len(module.ORIGINAL_RUNTIME)] = module.ISOLATED_RUNTIME.encode().ljust(
                    len(module.ORIGINAL_RUNTIME), b"\0")
                self.assertEqual(module.isolate_runtime(source, is_runtime=False), bytes(expected))

    def test_does_not_rewrite_incidental_strings_when_needed_reference_is_missing(self):
        source = elf_library(64, "libconsumer.so")
        with self.assertRaises(ValueError):
            module.isolate_runtime(source, is_runtime=False)

    def test_rejects_string_table_outside_load_segment(self):
        source = bytearray(elf_library(64, module.ORIGINAL_RUNTIME))
        struct.pack_into("<Q", source, 0x100 + 16 + 8, 100000)  # DT_STRSZ.
        with self.assertRaises(ValueError):
            module.isolate_runtime(bytes(source), is_runtime=True)

    def test_corrupt_elf_leaves_previous_output_intact_and_cleans_temporary_file(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.aar"
            output.write_bytes(b"previous verified archive")
            with self.assertRaises(ValueError):
                module.repackage(source_archive(corrupt_name="libsherpa-onnx-jni.so"), output)
            self.assertEqual(output.read_bytes(), b"previous verified archive")
            self.assertFalse(output.with_suffix(".tmp").exists())

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
