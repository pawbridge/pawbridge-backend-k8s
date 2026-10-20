#!/usr/bin/env python3
"""Select read-only CI checks and reject incomplete or publishing runs."""

import argparse
import json
import os
import re
import subprocess
import sys


SERVICES = (
    "api-gateway", "store-service", "user-service", "animal-service",
    "community-service", "payment-service",
)
GROUPS = {
    "gateway": SERVICES[:1],
    "store": SERVICES[1:2],
    "remaining": SERVICES[2:],
}
BRANCHES = {"refs/heads/main", "refs/heads/dev"}
ZERO_SHA = "0" * 40
SHA = re.compile(r"[0-9a-f]{40}")


class PolicyBlocked(ValueError):
    """Only fixed, non-sensitive error messages cross the CLI boundary."""


def require_check_only(value):
    if value != "false":
        raise PolicyBlocked("Publication is disabled; an explicit false is required.")


def validate_event(env):
    event = env.get("GITHUB_EVENT_NAME", "")
    ref = env.get("GITHUB_REF", "")
    manual = env.get("MANUAL_PUBLISH", "")
    if event == "workflow_dispatch":
        require_check_only(manual)
    elif manual not in ("", "false"):
        raise PolicyBlocked("Publication is disabled for every CI event.")

    if event == "pull_request":
        if env.get("PULL_REQUEST_BASE_REF", "") not in ("main", "dev"):
            raise PolicyBlocked("Pull requests must target main or dev.")
    elif event in ("push", "workflow_dispatch"):
        if ref not in BRANCHES:
            raise PolicyBlocked("Push and manual checks require main or dev.")
    else:
        raise PolicyBlocked("Unsupported CI event.")
    return event


def git(*args):
    try:
        return subprocess.run(
            ["git", *args], check=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        raise PolicyBlocked("Could not verify checked-out source or changed paths.") from None


def checked_sha(value):
    if not SHA.fullmatch(value) or value == ZERO_SHA:
        raise PolicyBlocked("A full nonzero source commit SHA is required.")
    return value


def changed_paths(env, event):
    head = checked_sha(env.get("GITHUB_SHA", ""))
    if git("rev-parse", "HEAD").decode().strip() != head:
        raise PolicyBlocked("The checked-out source differs from the CI source SHA.")
    if event == "workflow_dispatch":
        return []
    if event == "pull_request":
        base = checked_sha(env.get("PULL_REQUEST_BASE_SHA", ""))
    else:
        before = env.get("PUSH_BEFORE_SHA", "")
        if before == ZERO_SHA:
            raw = git("ls-tree", "-r", "--name-only", "-z", head)
            return [path.decode("utf-8", errors="surrogateescape")
                    for path in raw.split(b"\0") if path]
        base = checked_sha(before)
    raw = git("diff", "--name-only", "--no-renames", "-z", base, head, "--")
    return [path.decode("utf-8", errors="surrogateescape")
            for path in raw.split(b"\0") if path]


def select_services(group, env):
    event = validate_event(env)
    paths = changed_paths(env, event)
    candidates = GROUPS[group]
    if event == "workflow_dispatch":
        if group == "remaining":
            selected = env.get("MANUAL_SERVICE", "")
            if selected not in candidates:
                raise PolicyBlocked("Unknown service selected for manual CI.")
            return [selected]
        return list(candidates)

    def affected(service):
        for path in paths:
            if path.startswith(service + "/"):
                return True
            if path in (
                    ".github/scripts/backend_contract_profile.py",
                    ".github/scripts/test_backend_admin_post_reads_profile.py"):
                continue
            if path.startswith((".github/scripts/", ".github/workflows/")):
                return True
            if service != "api-gateway" and path.startswith(".github/migrations/"):
                return True
        return False

    return [service for service in candidates if affected(service)]


def verify_gate(needs, check, required):
    if not isinstance(needs, dict):
        raise PolicyBlocked("Expected CI dependency results.")
    selection = needs.get("selection")
    if not isinstance(selection, dict) or selection.get("result") != "success":
        raise PolicyBlocked("CI source selection did not succeed.")
    outputs = selection.get("outputs")
    if not isinstance(outputs, dict):
        raise PolicyBlocked("CI source selection outputs are missing.")
    selected = outputs.get("selected")
    count = outputs.get("count")
    if selected not in ("true", "false") or not isinstance(count, str):
        raise PolicyBlocked("Invalid CI source selection outputs.")
    if not re.fullmatch(r"0|[1-9][0-9]*", count):
        raise PolicyBlocked("Invalid selected service count.")
    try:
        matrix = json.loads(outputs.get("matrix", ""))
    except (TypeError, ValueError):
        raise PolicyBlocked("Invalid selected service matrix.") from None
    if not isinstance(matrix, dict) or set(matrix) != {"service"}:
        raise PolicyBlocked("Invalid selected service matrix.")
    services = matrix["service"]
    if (not isinstance(services, list)
            or any(not isinstance(s, str) or s not in SERVICES for s in services)
            or len(services) != len(set(services))
            or int(count) != len(services)
            or (selected == "true") != bool(services)):
        raise PolicyBlocked("Selected services and CI selection outputs disagree.")
    for name in required:
        result = needs.get(name)
        if not isinstance(result, dict) or result.get("result") != "success":
            raise PolicyBlocked("A required CI validation did not succeed.")
    result = needs.get(check)
    expected = "success" if services else "skipped"
    if not isinstance(result, dict) or result.get("result") != expected:
        raise PolicyBlocked("A required service check failed or was unexpectedly skipped.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    selection = commands.add_parser("select")
    selection.add_argument("--group", choices=GROUPS, required=True)
    gate = commands.add_parser("gate")
    gate.add_argument("--check", required=True)
    gate.add_argument("--required", action="append", default=[])
    guard = commands.add_parser("require-check-only")
    guard.add_argument("--publish-env", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "select":
            services = select_services(args.group, os.environ)
            print("selected=" + str(bool(services)).lower())
            print("matrix=" + json.dumps({"service": services}, separators=(",", ":")))
            print("count=" + str(len(services)))
        elif args.command == "gate":
            try:
                needs = json.loads(os.environ.get("NEEDS_JSON", ""))
            except ValueError:
                raise PolicyBlocked("Expected valid CI dependency results.") from None
            verify_gate(needs, args.check, args.required)
            print("Read-only CI checks satisfied the selected scope.")
        else:
            require_check_only(os.environ.get(args.publish_env, ""))
            print("Publication disabled; checks only.")
    except PolicyBlocked as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
