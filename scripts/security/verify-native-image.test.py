"""Regression coverage for native probe workspaces, deadlines and bounded cleanup."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "verify_native_image", Path(__file__).with_name("verify-native-image.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ProbeWorkspaceTests(unittest.TestCase):
    def test_main_makes_the_probe_directory_accessible_before_docker_runs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def inspect_image(*command):
                self.assertEqual(command, ("docker", "image", "inspect", "qa-image"))
                (work,) = (root / ".testhub").iterdir()
                self.assertEqual(work.stat().st_mode & 0o777, 0o755)
                config = json.dumps([{"Config": {"User": "root"}}])
                return subprocess.CompletedProcess(command, 0, config, "")

            with patch.object(VERIFY, "ROOT", root), \
                    patch.object(VERIFY, "run", side_effect=inspect_image), \
                    patch("sys.argv", ["verify-native-image.py", "qa-image"]):
                with self.assertRaisesRegex(AssertionError, "Nonroot image required"):
                    VERIFY.main()

            self.assertEqual(list((root / ".testhub").iterdir()), [])


class ProbeDeadlineTests(unittest.TestCase):
    def test_custom_deadline_preserves_bounded_cleanup(self):
        result = subprocess.CompletedProcess([], 0, "passed", "")
        with patch.object(VERIFY.subprocess, "run", return_value=result) as run:
            self.assertIs(VERIFY.restricted("qa-image", Path("/qa"), "java", "Probe", timeout=180), result)
        command = run.call_args_list[0].args[0]
        self.assertIn("--read-only", command)
        self.assertIn("/qa:/qa:ro", command)
        self.assertNotIn("--user", command)
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
