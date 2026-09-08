#!/usr/bin/env python3
"""Gate Store publication; registry reads never use or print a Docker Hub PAT."""

import argparse
import json
import os
import re
import sys
from http.client import HTTPException
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPOSITORY = "dorosiya/pawbridge-store-service"
MANIFEST_TYPES = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.docker.distribution.manifest.v2+json",
))


class ReleaseBlocked(ValueError):
    """Only fixed, non-sensitive messages may cross the CLI boundary."""


def publication_allowed(event: str, ref: str, manual_publish: str) -> bool:
    if event == "pull_request":
        return False
    if event == "push":
        if ref != "refs/heads/dev":
            raise ReleaseBlocked("Store publication requires the dev branch.")
        return True
    if event == "workflow_dispatch":
        if manual_publish not in ("", "false", "true"):
            raise ReleaseBlocked("Manual publish must be a boolean.")
        if manual_publish == "true" and ref != "refs/heads/dev":
            raise ReleaseBlocked("Manual publication requires the dev branch.")
        return manual_publish == "true"
    raise ReleaseBlocked("Unsupported Store CI event.")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(url: str, headers: dict) -> tuple[int, dict]:
    # Fixed HTTPS endpoints only; never forward the short-lived bearer on redirects.
    try:
        response = build_opener(NoRedirect).open(Request(url, headers=headers), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        status = response.code
        raw = response.read(65537)
    if len(raw) > 65536:
        raise ReleaseBlocked("Registry response exceeded the size limit.")
    body = json.loads(raw)
    if not isinstance(body, dict):
        raise ReleaseBlocked("Unexpected registry response.")
    return status, body


def require_unpublished_tag(sha: str, fetch=request_json) -> None:
    if re.fullmatch(r"[0-9a-f]{40}", sha) is None:
        raise ReleaseBlocked("Expected a full commit SHA.")
    status, auth = fetch(
        f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{REPOSITORY}:pull",
        {},
    )
    token = auth.get("token")
    if status != 200 or not isinstance(token, str) or not token:
        raise ReleaseBlocked("Registry read authorization could not be verified.")
    status, body = fetch(
        f"https://registry-1.docker.io/v2/{REPOSITORY}/manifests/sha-{sha}",
        {"Authorization": f"Bearer {token}", "Accept": MANIFEST_TYPES},
    )
    if status == 200:
        raise ReleaseBlocked("Store SHA tag already exists. Refusing to rebuild or overwrite it.")
    errors = body.get("errors")
    if (status != 404 or not isinstance(errors, list) or not errors
            or not all(isinstance(e, dict) and e.get("code") == "MANIFEST_UNKNOWN" for e in errors)):
        raise ReleaseBlocked("Registry did not confirm MANIFEST_UNKNOWN. Publication is blocked.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "check-tag"))
    args = parser.parse_args()
    try:
        if args.mode == "plan":
            publish = publication_allowed(
                os.environ.get("GITHUB_EVENT_NAME", ""),
                os.environ.get("GITHUB_REF", ""),
                os.environ.get("MANUAL_PUBLISH", ""),
            )
            print(f"publish={str(publish).lower()}")
        else:
            require_unpublished_tag(os.environ.get("GITHUB_SHA", ""))
            print("Store SHA tag is absent. No image or Infra resource was changed.")
    except (ValueError, OSError, HTTPException) as error:
        # Do not print HTTP response bodies, bearer tokens, or transport exception text.
        message = str(error) if isinstance(error, ReleaseBlocked) else "Registry check failed; publication is blocked."
        print(f"ERROR: {message}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
