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
    def test_workflows_preserve_external_steps_behind_literal_false(self):
        for filename, external_count, image_count in [
            ("api-gateway-ci.yml", 10, 1), ("store-service-ci.yml", 13, 2),
        ]:
            with self.subTest(workflow=filename):
                content = (SCRIPT.parents[1] / "workflows" / filename).read_text()
                build_job = content.split("  test-and-build:\n", 1)[1].split("\n  ci-result:", 1)[0]
                blocks = re.split(r"^      - name: ", build_job, flags=re.MULTILINE)[1:]
                steps = {block.splitlines()[0]: block for block in blocks}
                names = list(steps)
                external = names[names.index("Set up Helm"):]
                self.assertEqual(len(external), external_count)
                for name in external:
                    with self.subTest(step=name):
                        self.assertRegex(steps[name], r"(?m)^        if: \$\{\{ false \}\}$")
                images = [block for block in steps.values() if "uses: docker/build-push-action@" in block]
                self.assertEqual(len(images), image_count)
                for block in images:
                    self.assertRegex(block, r"(?m)^          push: false$")
                if filename == "store-service-ci.yml":
                    self.assertIn("STORE_PUBLISH_ALLOWED: 'false'", content)

    def test_supported_validation_events_never_allow_publication(self):
        cases = [
            ("pull_request", "refs/pull/1/merge", ""),
            ("pull_request", "refs/pull/2/merge", "false"),
            ("push", "refs/heads/dev", ""),
            ("push", "refs/heads/main", ""),
            ("workflow_dispatch", "refs/heads/dev", "false"),
            ("workflow_dispatch", "refs/heads/main", "false"),
        ]
        for event, ref, requested in cases:
            with self.subTest(event=event, ref=ref, requested=requested):
                self.assertFalse(MODULE.publication_allowed(event, ref, requested))

    def test_rejects_publication_wrong_manual_refs_and_malformed_events(self):
        cases = [
            ("pull_request", "refs/pull/1/merge", "true"),
            ("push", "refs/heads/dev", "true"),
            ("workflow_dispatch", "refs/heads/dev", "true"),
            ("workflow_dispatch", "refs/heads/main", "true"),
            ("push", "refs/heads/topic", ""),
            ("workflow_dispatch", "refs/heads/topic", "false"),
            ("workflow_dispatch", "refs/tags/dev", "false"),
            ("workflow_dispatch", "refs/heads/dev", ""),
            ("workflow_dispatch", "refs/heads/dev", "yes"),
            ("push", "refs/heads/dev", "False"),
            ("pull_request_target", "refs/heads/dev", "false"),
            ("", "refs/heads/dev", "false"),
        ]
        for event, ref, flag in cases:
            with self.subTest(event=event, ref=ref, flag=flag):
                with self.assertRaises(ValueError):
                    MODULE.publication_allowed(event, ref, flag)

    def test_plan_cli_outputs_only_false_or_fails_without_output(self):
        cases = [
            ("push", "refs/heads/dev", "", 0),
            ("push", "refs/heads/main", "", 0),
            ("pull_request", "refs/pull/1/merge", "", 0),
            ("workflow_dispatch", "refs/heads/dev", "false", 0),
            ("workflow_dispatch", "refs/heads/main", "false", 0),
            ("workflow_dispatch", "refs/heads/dev", "true", 1),
            ("workflow_dispatch", "refs/heads/topic", "false", 1),
            ("workflow_dispatch", "refs/heads/dev", "yes", 1),
            ("workflow_dispatch", "refs/heads/dev", "", 1),
            ("pull_request_target", "refs/heads/dev", "false", 1),
        ]
        for event, ref, flag, expected_exit in cases:
            with self.subTest(event=event, ref=ref, flag=flag):
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), "plan"], capture_output=True, text=True,
                    env={**os.environ, "GITHUB_EVENT_NAME": event,
                         "GITHUB_REF": ref, "MANUAL_PUBLISH": flag},
                )
                self.assertEqual(result.returncode, expected_exit)
                self.assertEqual(result.stdout, "publish=false\n" if expected_exit == 0 else "")


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
