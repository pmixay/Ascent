"""Condition waiting is exercised with fake time/HTTP, never a live CI loop."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import wait_for_ci as waiter

SHA = "a" * 40
JOB = "Build and validate Android app"


def run(status="completed", conclusion="success", **extra):
    return {"id": 42, "head_sha": SHA, "event": "push", "status": status, "conclusion": conclusion, **extra}


class CIWaiterTests(unittest.TestCase):
    def test_wrong_commits_events_and_older_runs_cannot_satisfy_condition(self):
        self.assertEqual(waiter.matching_run([
            run(id=1), run(id=99, head_sha="b" * 40), run(id=100, event="pull_request"), run(),
        ], SHA)["id"], 42)
        self.assertIsNone(waiter.matching_run([run(head_sha="b" * 40)], SHA))

    def test_success_requires_completed_run_and_every_required_job(self):
        jobs = [{"name": JOB, "status": "completed", "conclusion": "success"}]
        self.assertEqual(waiter.result_code(run(), jobs, [JOB]), 0)
        for candidate, required in ((run(status="in_progress"), [JOB]),
                                    (run(conclusion="cancelled"), [JOB]),
                                    (run(), [JOB, "offline"])):
            self.assertEqual(waiter.result_code(candidate, jobs, required), 1)
        for status, conclusion in (("completed", "skipped"), ("completed", "failure"), ("queued", "success")):
            self.assertEqual(waiter.result_code(run(), [{"name": JOB, "status": status, "conclusion": conclusion}], [JOB]), 1)

    def exercise(self, responses, timeout=100):
        client = Mock(poll_interval=0)
        client.get.side_effect = responses
        now = [0.0]
        with tempfile.TemporaryDirectory() as directory, patch.object(waiter.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(waiter.time, "sleep", side_effect=lambda delay: now.__setitem__(0, now[0] + delay)), \
                patch("builtins.print"):
            output = Path(directory) / "result.json"
            code = waiter.wait(client, "android.yml", SHA, timeout, 20, [JOB], output)
            result = json.loads(output.read_text()) if output.exists() else None
        return code, result, client

    def test_waits_through_absent_and_running_run_then_writes_exact_result(self):
        code, result, client = self.exercise([
            {"workflow_runs": []}, {"workflow_runs": [run(status="in_progress", conclusion=None)]},
            {"workflow_runs": [run()]}, {"jobs": [{"name": JOB, "status": "completed", "conclusion": "success"}]},
        ])
        self.assertEqual(code, 0)
        self.assertEqual(result["head_sha"], SHA)
        self.assertEqual(client.get.call_count, 4)

    def test_failed_run_returns_failure_even_with_passing_job(self):
        code, result, _ = self.exercise([{"workflow_runs": [run(conclusion="failure")]}, {"jobs": []}])
        self.assertEqual(code, 1)
        self.assertEqual(result["conclusion"], "failure")

    def test_timeout_and_forbidden_do_not_report_success(self):
        self.assertEqual(self.exercise([{"workflow_runs": []}], timeout=10)[0], 2)
        self.assertEqual(self.exercise([HTTPError("url", 403, "forbidden", {}, None)])[0], 2)

    def test_rate_limit_then_success(self):
        code, _, _ = self.exercise([
            HTTPError("url", 429, "rate limit", {"Retry-After": "5"}, None),
            {"workflow_runs": [run()]}, {"jobs": [{"name": JOB, "status": "completed", "conclusion": "success"}]},
        ])
        self.assertEqual(code, 0)

    def test_conditional_request_reuses_unchanged_response(self):
        response = io.BytesIO(b'{"workflow_runs": []}')
        response.headers = {"ETag": '"version-1"'}
        client = waiter.GitHub("owner/repo", "secret-test-value")
        with patch.object(waiter.urllib.request, "urlopen", side_effect=[
            response, HTTPError("url", 304, "unchanged", {}, None),
        ]) as request:
            first = client.get("actions/runs", 5)
            self.assertEqual(client.get("actions/runs", 5), first)
            self.assertEqual(request.call_args.args[0].get_header("If-none-match"), '"version-1"')


if __name__ == "__main__":
    unittest.main()
