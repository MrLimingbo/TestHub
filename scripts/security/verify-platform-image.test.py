"""Regression coverage for emulated API startup deadlines."""
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    "verify_platform_image", Path(__file__).with_name("verify-platform-image.py"))
ACCEPTANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACCEPTANCE)


class ReadinessTests(unittest.TestCase):
    def run_readiness(self, timeout, ready_at):
        elapsed = 0
        requests = []

        def sleep(seconds):
            nonlocal elapsed
            elapsed += seconds

        def request(path):
            requests.append(path)
            if elapsed < ready_at:
                raise OSError("API is still starting")
            return b'{"status":"UP"}'

        with patch.object(ACCEPTANCE.time, "monotonic", side_effect=lambda: elapsed), \
                patch.object(ACCEPTANCE.time, "sleep", side_effect=sleep):
            ACCEPTANCE.wait_ready(request, timeout)
        self.assertTrue(requests)
        self.assertEqual(set(requests), {"/actuator/health/readiness"})

    def test_extended_deadline_allows_starting_api_to_become_ready(self):
        self.run_readiness(timeout=120, ready_at=100)

    def test_default_deadline_still_fails_unready_api(self):
        with self.assertRaisesRegex(RuntimeError, "within 90 seconds"):
            self.run_readiness(timeout=90, ready_at=100)

    def test_ready_api_returns_immediately(self):
        self.run_readiness(timeout=90, ready_at=0)

    def test_cli_rejects_nonpositive_deadline_before_docker(self):
        for flag, timeout in [("--startup-timeout", "0"), ("--startup-timeout", "-1"),
                              ("--request-timeout", "0"), ("--request-timeout", "-1")]:
            with self.subTest(flag=flag, timeout=timeout), \
                    patch("sys.argv", ["verify-platform-image.py", "--context", "qa",
                                       flag, timeout, "testhub/api:qa"]), \
                    patch("sys.stderr", new_callable=io.StringIO) as errors:
                with self.assertRaises(SystemExit) as result:
                    ACCEPTANCE.main()
                self.assertEqual(result.exception.code, 2)
                self.assertIn("must be positive", errors.getvalue())

    def test_http_deadline_is_forwarded_to_opener(self):
        class ProbeFinished(Exception):
            pass

        for options, expected in [({}, 10), ({"request_timeout": 60}, 60)]:
            opener = Mock()
            opener.open.side_effect = ProbeFinished
            compose = Mock(return_value=b"127.0.0.1:12345")
            with self.subTest(options=options), \
                    patch.object(ACCEPTANCE.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(ProbeFinished):
                    ACCEPTANCE.verify_http(compose, "", **options)
            self.assertEqual(opener.open.call_args.kwargs["timeout"], expected)
            self.assertEqual(opener.open.call_args.args[0].full_url,
                             "http://127.0.0.1:12345/actuator/health/readiness")


if __name__ == "__main__":
    unittest.main()
