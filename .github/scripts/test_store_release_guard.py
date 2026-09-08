import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError


SCRIPT = Path(__file__).with_name("store_release_guard.py")
SPEC = importlib.util.spec_from_file_location("store_release_guard", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SHA = "a" * 40


class StorePublicationTest(unittest.TestCase):
    def test_workflow_does_not_trust_pr_script_output_alone_for_writes(self):
        workflow = SCRIPT.parents[1] / "workflows" / "store-service-ci.yml"
        content = workflow.read_text()
        self.assertIn(
            "STORE_PUBLISH_ALLOWED: ${{ github.ref == 'refs/heads/dev' && "
            "(github.event_name == 'push' || (github.event_name == 'workflow_dispatch' && inputs.publish)) }}",
            content,
        )
        blocks = re.split(r"^      - name: ", content, flags=re.MULTILINE)[1:]
        steps = {block.splitlines()[0]: block for block in blocks}
        gate = "if: env.STORE_PUBLISH_ALLOWED == 'true' && steps.release.outputs.publish == 'true'"
        for name in [
            "Set up Helm", "Refuse an existing or unverified Store image tag",
            "Log in to Docker Hub", "Verify published Store Service image digest",
            "Check out Infra repository", "Update Store image reference",
            "Validate Store image Helm override", "Create Infra pull request",
        ]:
            with self.subTest(step=name):
                self.assertIn(gate, steps[name])
        self.assertIn(
            "push: ${{ env.STORE_PUBLISH_ALLOWED == 'true' && steps.release.outputs.publish == 'true' }}",
            steps["Build and optionally publish Store Service image"],
        )

    def test_event_and_ref_publication_contract(self):
        cases = [
            ("pull_request", "refs/pull/1/merge", "true", False),
            ("push", "refs/heads/dev", "", True),
            ("workflow_dispatch", "refs/heads/dev", "", False),
            ("workflow_dispatch", "refs/heads/dev", "false", False),
            ("workflow_dispatch", "refs/heads/dev", "true", True),
            ("workflow_dispatch", "refs/heads/topic", "false", False),
        ]
        for event, ref, requested, expected in cases:
            with self.subTest(event=event, ref=ref, requested=requested):
                self.assertEqual(MODULE.publication_allowed(event, ref, requested), expected)

    def test_rejects_non_dev_publication_and_unknown_inputs(self):
        for event, ref, flag in [
            ("push", "refs/heads/topic", ""),
            ("workflow_dispatch", "refs/heads/topic", "true"),
            ("workflow_dispatch", "refs/tags/dev", "true"),
            ("workflow_dispatch", "refs/heads/dev", "yes"),
            ("pull_request_target", "refs/heads/dev", "true"),
        ]:
            with self.subTest(event=event, ref=ref, flag=flag):
                with self.assertRaises(ValueError):
                    MODULE.publication_allowed(event, ref, flag)

    def test_plan_cli_outputs_only_decision_and_fails_without_output(self):
        for ref, expected_exit, output in [
            ("refs/heads/dev", 0, "publish=true\n"),
            ("refs/heads/topic", 1, ""),
        ]:
            with self.subTest(ref=ref):
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), "plan"], capture_output=True, text=True,
                    env={**os.environ, "GITHUB_EVENT_NAME": "workflow_dispatch",
                         "GITHUB_REF": ref, "MANUAL_PUBLISH": "true"},
                )
                self.assertEqual(result.returncode, expected_exit)
                self.assertEqual(result.stdout, output)


class StoreTagGuardTest(unittest.TestCase):
    def test_only_confirmed_manifest_unknown_allows_publish(self):
        fetch = Mock(side_effect=[
            (200, {"token": "test-only-read-token"}),
            (404, {"errors": [{"code": "MANIFEST_UNKNOWN"}]}),
        ])
        MODULE.require_unpublished_tag(SHA, fetch)
        self.assertEqual(fetch.call_count, 2)
        url, headers = fetch.call_args.args
        self.assertEqual(url, f"https://registry-1.docker.io/v2/{MODULE.REPOSITORY}/manifests/sha-{SHA}")
        self.assertEqual(headers["Authorization"], "Bearer test-only-read-token")
        self.assertIn("application/vnd.oci.image.index.v1+json", headers["Accept"])

    def test_existing_tag_and_uncertain_registry_responses_block_publish(self):
        cases = [
            (200, {"schemaVersion": 2}),
            (401, {"errors": [{"code": "UNAUTHORIZED"}]}),
            (429, {"errors": [{"code": "TOOMANYREQUESTS"}]}),
            (500, {}),
            (404, {}),
            (404, {"errors": []}),
            (404, {"errors": [{"code": "NAME_UNKNOWN"}]}),
            (404, {"errors": [{"code": "MANIFEST_UNKNOWN"}, {"code": "UNAUTHORIZED"}]}),
            (404, {"errors": ["MANIFEST_UNKNOWN"]}),
        ]
        for response in cases:
            with self.subTest(response=response):
                fetch = Mock(side_effect=[(200, {"token": "test-token"}), response])
                with self.assertRaises(ValueError):
                    MODULE.require_unpublished_tag(SHA, fetch)

    def test_invalid_sha_and_auth_fail_before_manifest_read(self):
        fetch = Mock()
        for sha in ["", "short", "../dev", "A" * 40]:
            with self.assertRaises(ValueError):
                MODULE.require_unpublished_tag(sha, fetch)
        fetch.assert_not_called()
        for auth in [(401, {}), (200, {}), (200, {"token": 123})]:
            with self.subTest(auth=auth):
                fetch = Mock(return_value=auth)
                with self.assertRaises(ValueError):
                    MODULE.require_unpublished_tag(SHA, fetch)
                self.assertEqual(fetch.call_count, 1)

    def test_http_error_body_is_checked_instead_of_treating_any_404_as_missing(self):
        body = {"errors": [{"code": "MANIFEST_UNKNOWN"}]}
        error = HTTPError("https://registry-1.docker.io/example", 404, "missing",
                          {}, io.BytesIO(json.dumps(body).encode()))
        with patch.object(MODULE, "build_opener") as opener:
            opener.return_value.open.side_effect = error
            self.assertEqual(MODULE.request_json("https://registry-1.docker.io/example", {}), (404, body))

    def test_transport_error_is_suppressed_at_cli_boundary(self):
        for error in [URLError("test-secret"), ValueError("test-secret"), MODULE.HTTPException("test-secret")]:
            with self.subTest(error=type(error).__name__), \
                    patch.object(sys, "argv", [str(SCRIPT), "check-tag"]), \
                    patch.object(MODULE, "require_unpublished_tag", side_effect=error), \
                    patch("sys.stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(MODULE.main(), 1)
                self.assertNotIn("test-secret", stderr.getvalue())
                self.assertIn("publication is blocked", stderr.getvalue())

    def test_response_size_and_json_shape_are_bounded(self):
        for content in [b"x" * 65537, b"[]", b"not json"]:
            with self.subTest(content_length=len(content)):
                response = io.BytesIO(content)
                response.code = 200
                with patch.object(MODULE, "build_opener") as opener:
                    opener.return_value.open.return_value = response
                    with self.assertRaises(ValueError):
                        MODULE.request_json("https://registry-1.docker.io/example", {})

    def test_redirect_is_not_followed(self):
        self.assertIsNone(MODULE.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.test"))


if __name__ == "__main__":
    unittest.main()
