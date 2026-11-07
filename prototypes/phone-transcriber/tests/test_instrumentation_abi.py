"""CI-only classfile-fixture checks; no compiler, model or application execution."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from prepare_test_abi import prepare, references


def fixture(*strings):
    return b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 61, len(strings) + 1) + b''.join(
        b'\x01' + struct.pack('>H', len(value.encode())) + value.encode() for value in strings)


class InstrumentationAbiTest(unittest.TestCase):
    def test_preserves_class_and_constructor_descriptor_only_types(self):
        result = references(fixture('androidx/work/WorkManager',
            '(Lcom/pmixay/ascent/TranscriptSegment;ILkotlin/jvm/internal/DefaultConstructorMarker;)V',
            'java/lang/String', 'not a class'))
        self.assertEqual({'androidx.work.WorkManager', 'com.pmixay.ascent.TranscriptSegment',
                          'kotlin.jvm.internal.DefaultConstructorMarker'}, result)

    def test_missing_compiled_tests_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, 'No compiled instrumentation'):
                prepare(root, root / 'rules.pro')

    def test_generation_is_deterministic_and_not_a_whole_app_keep(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'Test.class').write_bytes(fixture('com/pmixay/ascent/TranscriptSegment'))
            output = root / 'rules.pro'
            prepare(root, output)
            text = output.read_text()
            self.assertIn('-keep,includedescriptorclasses class com.pmixay.ascent.TranscriptSegment { *; }', text)
            self.assertNotIn('com.pmixay.ascent.**', text)
            prepare(root, output)
            self.assertEqual(text, output.read_text())
