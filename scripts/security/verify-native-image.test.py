"""Regression coverage for emulated Java probe deadlines and bounded cleanup."""
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "verify_native_image", Path(__file__).with_name("verify-native-image.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ProbeDeadlineTests(unittest.TestCase):
    def test_custom_deadline_preserves_bounded_cleanup(self):
        result = subprocess.CompletedProcess([], 0, "passed", "")
        with patch.object(VERIFY.subprocess, "run", return_value=result) as run:
            self.assertIs(VERIFY.restricted("qa-image", Path("/qa"), "java", "Probe", timeout=180), result)
        self.assertEqual(run.call_args_list[0].kwargs["timeout"], 180)
        self.assertEqual(run.call_args_list[1].kwargs["timeout"], 30)
        self.assertEqual(run.call_args_list[1].args[0][:3], ["docker", "rm", "-f"])

    def test_default_deadline_is_thirty_seconds(self):
        with patch.object(VERIFY.subprocess, "run") as run:
            VERIFY.restricted("qa-image", Path("/qa"), "java", "Probe")
        self.assertEqual(run.call_args_list[0].kwargs["timeout"], 30)

    def test_cli_rejects_nonpositive_deadline_before_docker(self):
        for timeout in ("0", "-1"):
            with self.subTest(timeout=timeout), \
                    patch("sys.argv", ["verify-native-image.py", "qa-image", "--java-probe-timeout", timeout]), \
                    patch("sys.stderr", new_callable=io.StringIO) as errors:
                with self.assertRaises(SystemExit) as result:
                    VERIFY.main()
                self.assertEqual(result.exception.code, 2)
                self.assertIn("must be positive", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
