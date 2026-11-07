"""CI-only driver tests; Gradle and adb are mocked, never invoked here."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import run_android_instrumentation as driver


class InstrumentationDriverTest(unittest.TestCase):
    def test_requires_both_gradle_and_evidence_success(self):
        for code, evidence in ((0, True), (1, True), (0, False), (1, False)):
            with self.subTest(code=code, evidence=evidence), \
                    patch.object(driver.subprocess, 'run', return_value=SimpleNamespace(returncode=code)), \
                    patch.object(driver, 'verify', side_effect=None if evidence else ValueError('missing cases')), \
                    patch.object(driver, 'diagnose_device') as diagnose:
                self.assertEqual(code or (0 if evidence else 1), driver.run(['example#case']))
                self.assertEqual(bool(code or not evidence), diagnose.called)

    def test_embedded_still_selects_exact_class(self):
        with patch.object(driver.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as execute, \
                patch.object(driver, 'verify'):
            self.assertEqual(0, driver.run(['example#case'], embedded=True))
            command = execute.call_args.args[0]
            self.assertIn('-PascentEmbedded=true', command)
            self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class=com.pmixay.ascent.EmbeddedModelRuntimeTest', command)
