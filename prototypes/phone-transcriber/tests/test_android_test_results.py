"""CI-only regression checks for required instrumentation evidence."""
import sys
from pathlib import Path
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from verify_android_tests import verify


class AndroidTestResultsTest(unittest.TestCase):
    required = ["example.RuntimeTest#actualCase"]

    def test_requires_executed_case_not_just_an_empty_successful_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for xml in (None, '<testsuite tests="0"/>', '<testsuite><testcase classname="other" name="case"/></testsuite>'):
                with self.subTest(xml=xml):
                    if xml:
                        (root / "result.xml").write_text(xml)
                    with self.assertRaisesRegex(ValueError, "did not execute"):
                        verify(root, self.required)

    def test_skips_errors_and_failures_are_never_accepted(self):
        for issue in ("skipped", "error", "failure"):
            with self.subTest(issue=issue), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "result.xml").write_text(f'<testsuite><testcase classname="example.RuntimeTest" name="actualCase"><{issue}/></testcase></testsuite>')
                with self.assertRaises(ValueError):
                    verify(root, self.required)

    def test_passing_required_case_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "result.xml").write_text('<testsuites><testsuite><testcase classname="example.RuntimeTest" name="actualCase"/></testsuite></testsuites>')
            self.assertEqual(1, verify(root, self.required))
