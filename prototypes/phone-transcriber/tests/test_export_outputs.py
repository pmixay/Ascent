"""Validate export destinations without installing ML dependencies or running inference."""
from contextlib import redirect_stderr
import io
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from export_model_pack import arguments


class ExportOutputTests(unittest.TestCase):
    required = ["--speaker-model", "speaker.onnx", "--speaker-sha256", "a" * 64,
                "--speaker-license", "LICENSE", "--speaker-source", "https://example.invalid/speaker",
                "--smoke-wav", "smoke.wav"]

    def test_directory_only_skips_transport_destination(self):
        args = arguments(self.required + ["--directory-output", "flat"])
        self.assertEqual(args.directory_output, Path("flat"))
        self.assertIsNone(args.output)

    def test_server_transport_and_both_outputs_remain_supported(self):
        for extra in ([], ["--directory-output", "flat"]):
            self.assertEqual(arguments(self.required + ["--output", "models.zip"] + extra).output, Path("models.zip"))

    def test_missing_destination_is_rejected(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            arguments(self.required)
        self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
