#!/usr/bin/env python3
"""Reference one executed PR profile for its identical-tree, two-parent dev merge."""

import argparse
import ast
import base64
import binascii
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

import backend_contract_profile as profile
import ci_branch_policy as policy


WORKFLOW = '.github/workflows/backend-app-check.yml'
OUTPUT = Path('build/ci/postmerge-proof.txt')
MAX_JSON = 4 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_MEMBER = 8 * 1024 * 1024
MAX_UNPACKED = 256 * 1024 * 1024
MAX_PAGES = 3
MAX_AGE = dt.timedelta(hours=24)
PACKAGE_MEMBERS = ('build/ci/runtime-metadata.txt', 'build/ci/boot-jar.sha256')
FULL_MEMBERS = PACKAGE_MEMBERS + ('build/ci/contract-plan.txt', 'build/ci/contract-execution.txt')
ALLOWED_STEP_CONDITIONS = {'always()', "${{ matrix.profile == 'full' }}",
                           "${{ success() && matrix.profile == 'full' }}",
                           "${{ matrix.profile == 'note-expiry' }}",
                           "${{ matrix.profile != 'note-expiry' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' }}",
                           "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' }}",
                           "${{ matrix.profile != 'signup-statistics' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != 'signup-statistics' }}",
                           "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' }}",
                           "${{ matrix.profile == 'admin-member-update' }}",
                           "${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != 'admin-member-update' }}",
                           "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' }}",
                           "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' }}"}
ALLOWED_STEP_CONDITIONS.update({"${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' }}",
 "${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != "
 "'visit-collection' }}",
 "${{ matrix.profile == 'admin-member-update' }}",
 "${{ matrix.profile == 'full' || matrix.profile == 'visit-collection' }}",
 "${{ matrix.profile == 'full' }}",
 "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == "
 "'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' }}",
 "${{ matrix.profile == 'visit-collection' }}"})
ALLOWED_STEP_CONDITIONS.update({"${{ always() && (matrix.profile == 'full' || matrix.profile == 'auth-sessions') && "
 "(steps.fixture_auth_sessions.outcome == 'success' || steps.fixture_auth_sessions.outcome == 'failure') }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && "
 "matrix.profile != 'auth-sessions' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' }}",
 "${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != "
 "'visit-collection' && matrix.profile != 'auth-sessions' }}",
 "${{ matrix.profile == 'full' || matrix.profile == 'auth-sessions' }}",
 "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == "
 "'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || "
 "matrix.profile == 'auth-sessions' }}"})

# Exact reviewed post-statistics conditions; unknown expressions still fall back.
ALLOWED_STEP_CONDITIONS.update({"${{ matrix.profile != 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && "
 "matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' && matrix.profile != 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-post-statistics' }}",
 "${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != "
 "'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' }}",
 "${{ matrix.profile == 'full' || matrix.profile == 'admin-post-statistics' }}",
 "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == "
 "'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || "
 "matrix.profile == 'auth-sessions' || matrix.profile == 'admin-post-statistics' }}"})

# Exact administrator post read conditions; unknown expressions remain full fallback.
ALLOWED_STEP_CONDITIONS.update({"${{ matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && "
 "matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != "
 "'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' && matrix.profile != 'admin-post-statistics' && "
 "matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != "
 "'signup-statistics' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile "
 "!= 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != "
 "'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != "
 "'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && "
 "matrix.profile != 'user-community-integration' }}",
 "${{ matrix.profile == 'full' || matrix.profile == 'user-community-integration' }}",
 "${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == "
 "'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || "
 "matrix.profile == 'auth-sessions' || matrix.profile == 'admin-post-statistics' || matrix.profile == "
 "'user-community-integration' }}"})

class Unavailable(ValueError):
    """A fixed reason selects ordinary full; never expose remote error content."""


def require(condition, reason):
    if not condition:
        raise Unavailable(reason)


def sha(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{40}', value)
            and value != '0' * 40, 'invalid-identity')
    return value


def number(value):
    require(type(value) is int and value > 0, 'invalid-identity')
    return value


def document(raw):
    require(isinstance(raw, bytes) and len(raw) <= MAX_JSON, 'invalid-document')
    try:
        return profile.load_json(raw.decode('utf-8'))
    except (UnicodeError, profile.ProfileBlocked):
        raise Unavailable('invalid-document') from None


def timestamp(value):
    try:
        require(isinstance(value, str) and value.endswith('Z'), 'invalid-time')
        result = dt.datetime.fromisoformat(value[:-1] + '+00:00')
    except (ValueError, TypeError, OverflowError):
        raise Unavailable('invalid-time') from None
    return result


def git_read(*args):
    try:
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise Unavailable('git-unavailable') from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubReader:
    """Fixed GitHub GETs, bounded pages/body/time; no authenticated blob redirect."""

    def __init__(self, token):
        require(isinstance(token, str) and bool(token) and '\n' not in token
                and '\r' not in token, 'api-unavailable')
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)
        self.deadline = time.monotonic() + 120

    def _read(self, path, archive=False):
        require(path.startswith('/repos/' + profile.REPOSITORY + '/') and
                not any(char in path for char in ('\r', '\n', '#')), 'invalid-api-path')
        url = 'https://api.github.com' + path
        limit = MAX_ARCHIVE if archive else MAX_JSON
        for redirect in range(4):
            headers = {'Accept': 'application/vnd.github+json',
                       'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'pawbridge-postmerge-proof'}
            if redirect == 0:
                headers['Authorization'] = 'Bearer ' + self.token
            request = urllib.request.Request(url, headers=headers, method='GET')
            try:
                remaining = self.deadline - time.monotonic()
                require(remaining > 0, 'api-budget-exhausted')
                with self.opener.open(request, timeout=min(10, remaining)) as response:
                    length = response.headers.get('Content-Length')
                    require(length is None or (length.isdigit() and int(length) <= limit),
                            'api-size-limit')
                    data = response.read(limit + 1)
                    require(len(data) <= limit, 'api-size-limit')
                    return data
            except urllib.error.HTTPError as error:
                if not archive or error.code not in (301, 302, 303, 307, 308) or redirect == 3:
                    raise Unavailable('api-unavailable') from None
                location = error.headers.get('Location', '')
                try:
                    parsed = urllib.parse.urlsplit(location)
                    host, port = parsed.hostname or '', parsed.port
                except ValueError:
                    raise Unavailable('unsafe-artifact-redirect') from None
                require(parsed.scheme == 'https' and parsed.username is None and
                        parsed.password is None and port in (None, 443) and
                        (host.endswith('.blob.core.windows.net') or
                         host.endswith('.actions.githubusercontent.com') or
                         host.endswith('.githubusercontent.com')), 'unsafe-artifact-redirect')
                url = location  # Every redirect is fetched without the API token.
            except (OSError, TimeoutError, urllib.error.URLError):
                raise Unavailable('api-unavailable') from None
        raise Unavailable('api-unavailable')

    def json(self, path):
        return document(self._read(path))

    def archive(self, artifact_id):
        return self._read('/repos/' + profile.REPOSITORY + '/actions/artifacts/' +
                          str(number(artifact_id)) + '/zip', archive=True)


def pages(api, path, key=None):
    values = []
    seen = set()
    total = None
    for page in range(1, MAX_PAGES + 1):
        raw = api.json(path + ('&' if '?' in path else '?') + 'per_page=100&page=' + str(page))
        if key is None:
            batch = raw
        else:
            require(isinstance(raw, dict), 'invalid-api-document')
            count = raw.get('total_count')
            require(type(count) is int and 0 <= count <= 100 * MAX_PAGES,
                    'api-pagination-limit')
            require(total is None or total == count, 'api-pagination-changed')
            total = count
            batch = raw.get(key)
        require(isinstance(batch, list) and len(batch) <= 100, 'invalid-api-document')
        for item in batch:
            require(isinstance(item, dict), 'invalid-api-document')
            identity = item.get('id', item.get('number'))
            require(type(identity) is int and identity > 0 and identity not in seen,
                    'duplicate-api-identity')
            seen.add(identity)
        values.extend(batch)
        if len(batch) < 100:
            require(total is None or total == len(values), 'api-pagination-changed')
            return values
    require(total == len(values), 'api-pagination-limit')
    return values


def zip_members(raw, wanted):
    require(isinstance(raw, bytes) and len(raw) <= MAX_ARCHIVE, 'invalid-artifact')
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            require(len(infos) <= 4000 and sum(i.file_size for i in infos) <= MAX_UNPACKED,
                    'artifact-size-limit')
            names = set()
            for info in infos:
                path = PurePosixPath(info.filename)
                mode = info.external_attr >> 16
                require(0 < len(info.filename) <= 512 and not path.is_absolute() and
                        all(part not in ('', '..', '.') for part in info.filename.rstrip('/').split('/')) and
                        not any(ord(c) < 32 or c in '\\:' for c in info.filename) and
                        '\x00' not in info.filename and info.filename not in names and
                        stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR) and
                        not (info.flag_bits & 1), 'unsafe-artifact-member')
                require(info.file_size <= MAX_MEMBER, 'artifact-size-limit')
                names.add(info.filename)
            require(set(wanted).issubset(names), 'missing-artifact-member')
            return {name: archive.read(name) for name in wanted}
    except (zipfile.BadZipFile, RuntimeError, OSError, EOFError, ValueError):
        raise Unavailable('invalid-artifact') from None


def cohort_condition(condition):
    """Only the already allowlisted workflow expressions may select cohort steps."""
    require(condition in ALLOWED_STEP_CONDITIONS, 'unknown-workflow-condition')
    if 'steps.fixture_auth_sessions.outcome' in condition:
        # This exact known restore expression is full/auth-only, never the cohort.
        require("matrix.profile == 'full' || matrix.profile == 'auth-sessions'" in condition,
                'unknown-workflow-condition')
        return False
    expression = condition.removeprefix('${{').removesuffix('}}').strip()
    expression = expression.replace('always()', 'True').replace('success()', 'True')
    expression = re.sub(r"matrix[.]profile (==|!=) '([^']+)'",
        lambda match: str((profile.POST_READS == match.group(2)) == (match.group(1) == '==')), expression)
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    try:
        tree = ast.parse(expression, mode='eval')
    except (SyntaxError, ValueError):
        raise Unavailable('unknown-workflow-condition') from None
    def value(node):
        if isinstance(node, ast.Expression): return value(node.body)
        if isinstance(node, ast.Constant) and type(node.value) is bool: return node.value
        if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            values = [value(child) for child in node.values]
            return all(values) if isinstance(node.op, ast.And) else any(values)
        raise Unavailable('unknown-workflow-condition')
    return value(tree)


def workflow_contract(raw, selected_profile=profile.FULL):
    require(isinstance(raw, bytes) and len(raw) <= MAX_JSON, 'unknown-workflow')
    try:
        text = raw.decode('utf-8')
    except UnicodeError:
        raise Unavailable('unknown-workflow') from None
    require(selected_profile in (profile.FULL, profile.POST_READS), 'unsupported-source-profile')
    result = {}
    for key in ('package', 'check', 'ci-result', 'proof'):
        match = re.search(r'^  ' + key + r':\n(.*?)(?=^  [A-Za-z][A-Za-z0-9_-]*:|\Z)',
                          text, re.MULTILINE | re.DOTALL)
        require(match is not None, 'unknown-workflow')
        body = match.group(1)
        name = re.search(r'^    name: (.+)$', body, re.MULTILINE)
        require(name is not None and 'continue-on-error' not in body, 'unknown-workflow')
        name = name.group(1)
        if key == 'check':
            expression = re.fullmatch(r"\$\{\{ matrix.profile == 'full' && '([^']+)' \|\| (?:matrix.profile == 'note-expiry' && '[^']+' \|\| )?(?:matrix.profile == 'member-management' && '[^']+' \|\| )?(?:matrix.profile == 'signup-statistics' && '[^']+' \|\| )?(?:matrix.profile == 'admin-member-update' && '[^']+' \|\| )?(?:matrix.profile == 'visit-collection' && '[^']+' \|\| )?(?:matrix.profile == 'auth-sessions' && '[^']+' \|\| )?(?:matrix.profile == 'admin-post-statistics' && '[^']+' \|\| )?(?:matrix.profile == 'user-community-integration' && '[^']+' \|\| )?'[^']+' \}\}", name)
            require(expression is not None, 'unknown-workflow')
            if selected_profile == profile.POST_READS:
                scoped = re.search(r"matrix.profile == 'user-community-integration' && '([^']+)'", name)
                require(scoped is not None, 'unknown-workflow')
                name = scoped.group(1)
            else:
                name = expression.group(1)
        require('${{' not in name, 'unknown-workflow')
        steps = []
        for part in re.split(r'(?=^      - )', body, flags=re.MULTILINE)[1:]:
            step = re.match(r'      - name: ([^\n]+)\n', part)
            require(step is not None and '${{' not in step.group(1), 'unknown-workflow')
            condition = re.findall(r'^        if: (.+)$', part, re.MULTILINE)
            require(len(condition) <= 1 and all(c in ALLOWED_STEP_CONDITIONS for c in condition),
                    'unknown-workflow-condition')
            if selected_profile == profile.POST_READS:
                if not condition or cohort_condition(condition[0]):
                    steps.append(step.group(1))
                continue
            # Preserve the existing full recipe and its full-only coverage.
            if condition not in (["${{ matrix.profile == 'note-expiry' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' }}"],
                                  ["${{ matrix.profile == 'admin-member-update' }}"],
                                  ["${{ matrix.profile == 'visit-collection' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || matrix.profile == 'auth-sessions' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || matrix.profile == 'auth-sessions' || matrix.profile == 'admin-post-statistics' }}"],
                                  ["${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || matrix.profile == 'auth-sessions' || matrix.profile == 'admin-post-statistics' || matrix.profile == 'user-community-integration' }}"]):
                steps.append(step.group(1))
        require(bool(steps) and len(steps) == len(set(steps)), 'unknown-workflow')
        result[key] = (name, steps)
    return result


def metadata(raw, identity):
    try:
        text = raw.decode('utf-8')
    except UnicodeError:
        raise Unavailable('invalid-runtime-metadata') from None
    for key in ('source_sha', 'workflow_sha', 'workflow_ref'):
        values = re.findall(r'^' + key + r'=(.*)$', text, re.MULTILINE)
        require(values == [identity[key]], 'runtime-identity-differs')


def jar_digest(raw):
    require(isinstance(raw, bytes), 'invalid-jar-digest')
    match = re.fullmatch(rb'([0-9a-f]{64})  boot/build/libs/pawbridge-backend-app\.jar\n', raw)
    require(match is not None, 'invalid-jar-digest')
    return match.group(1).decode('ascii')


def context(env, git):
    try:
        event = policy.validate_event(env)
    except policy.PolicyBlocked:
        raise Unavailable('unsupported-event') from None
    require(event in ('pull_request', 'push') and env.get('GITHUB_REPOSITORY') == profile.REPOSITORY,
            'unsupported-event')
    source = sha(env.get('GITHUB_SHA'))
    require(git('rev-parse', 'HEAD').decode('ascii').strip() == source, 'checkout-differs')
    tree = sha(git('rev-parse', 'HEAD^{tree}').decode('ascii').strip())
    workflow_sha = sha(env.get('GITHUB_WORKFLOW_SHA'))
    workflow_ref = env.get('GITHUB_WORKFLOW_REF')
    require(isinstance(workflow_ref, str) and len(workflow_ref) < 512, 'invalid-identity')
    run_id, attempt = env.get('GITHUB_RUN_ID', ''), env.get('GITHUB_RUN_ATTEMPT', '')
    require(re.fullmatch('[1-9][0-9]*', run_id) and re.fullmatch('[1-9][0-9]*', attempt),
            'invalid-identity')
    try:
        payload = document(Path(env['GITHUB_EVENT_PATH']).read_bytes())
    except (KeyError, OSError):
        raise Unavailable('event-unavailable') from None
    require(isinstance(payload, dict), 'invalid-document')
    if event == 'pull_request':
        base = sha(env.get('PULL_REQUEST_BASE_SHA'))
    else:
        require(env.get('GITHUB_REF') == 'refs/heads/dev' and
                payload.get('ref') == 'refs/heads/dev' and
                payload.get('forced') is False and payload.get('deleted') is False,
                'unsupported-push')
        base = sha(env.get('PUSH_BEFORE_SHA'))
        require(payload.get('before') == base and payload.get('after') == source,
                'push-identity-differs')
    identity = dict(repository=profile.REPOSITORY, event_name=event, source_sha=source,
                    before_sha=base, tree_sha=tree, workflow_sha=workflow_sha,
                    workflow_ref=workflow_ref, run_id=run_id, run_attempt=attempt)
    raw = git('show', workflow_sha + ':' + WORKFLOW)
    require(raw == git('show', source + ':' + WORKFLOW), 'executed-workflow-differs')
    return identity, payload, raw


def one_job(jobs, name, now):
    matches = [job for job in jobs if job.get('name') == name]
    require(len(matches) == 1, 'missing-or-duplicate-job')
    job = matches[0]
    require(job.get('status') == 'completed' and job.get('conclusion') == 'success',
            'source-job-not-successful')
    completed = timestamp(job.get('completed_at'))
    require(completed <= now, 'future-source-time')
    return job, completed


def verify(identity, payload, workflow, git, api, now):
    prefix = '/repos/' + profile.REPOSITORY
    before, merged = identity['before_sha'], identity['source_sha']
    require(identity['workflow_ref'] == profile.REPOSITORY + '/' + WORKFLOW + '@refs/heads/dev',
            'workflow-ref-differs')
    parents = git('show', '-s', '--format=%P', merged).decode('ascii').strip().split()
    require(len(parents) == 2 and parents[0] == before, 'not-one-two-parent-merge')
    head = sha(parents[1])
    associated = pages(api, prefix + '/commits/' + merged + '/pulls')
    require(len(associated) == 1, 'not-one-associated-pr')
    pr_number = number(associated[0].get('number'))
    pr = api.json(prefix + '/pulls/' + str(pr_number))
    require(isinstance(pr, dict) and pr.get('number') == pr_number and
            pr.get('merged') is True and pr.get('state') == 'closed' and
            pr.get('merge_commit_sha') == merged and isinstance(pr.get('head'), dict) and
            isinstance(pr.get('base'), dict), 'pr-merge-differs')
    require(pr['head'].get('sha') == head and pr['base'].get('ref') == 'dev' and
            all(isinstance(pr[side].get('repo'), dict) and
                pr[side]['repo'].get('full_name') == profile.REPOSITORY for side in ('head', 'base')),
            'pr-repository-or-head-differs')
    head_ref = pr['head'].get('ref')
    require(isinstance(head_ref, str) and head_ref and len(head_ref) < 256 and
            not any(ord(c) < 32 for c in head_ref), 'invalid-identity')
    merged_at = timestamp(pr.get('merged_at'))
    require(merged_at <= now, 'future-source-time')
    runs = pages(api, prefix + '/actions/workflows/backend-app-check.yml/runs?event=pull_request&head_sha=' + head,
                 'workflow_runs')
    require(bool(runs), 'no-source-run')
    run = max(runs, key=lambda value: number(value.get('id')))
    run_id, attempt = number(run.get('id')), number(run.get('run_attempt'))
    require(run.get('event') == 'pull_request' and run.get('head_sha') == head and
            run.get('path') == WORKFLOW and run.get('status') == 'completed' and
            run.get('conclusion') == 'success' and
            isinstance(run.get('repository'), dict) and
            run['repository'].get('full_name') == profile.REPOSITORY and
            isinstance(run.get('head_repository'), dict) and
            run['head_repository'].get('full_name') == profile.REPOSITORY,
            'latest-source-run-not-successful')
    require(isinstance(run.get('pull_requests'), list) and
            (not run['pull_requests'] or
             (len(run['pull_requests']) == 1 and isinstance(run['pull_requests'][0], dict) and
              run['pull_requests'][0].get('number') == pr_number)),
            'source-run-pr-differs')
    number(run.get('workflow_id'))
    repository_id = number(run['repository'].get('id'))
    head_repository_id = number(run['head_repository'].get('id'))
    # Detail protects against a stale listing/rerun race; never fall back to an older green.
    current = api.json(prefix + '/actions/runs/' + str(run_id))
    require(isinstance(current, dict) and all(current.get(k) == run.get(k) for k in
            ('id', 'run_attempt', 'event', 'head_sha', 'path', 'status', 'conclusion', 'workflow_id')),
            'source-run-changed')
    jobs = pages(api, prefix + '/actions/runs/' + str(run_id) + '/attempts/' + str(attempt) + '/jobs', 'jobs')
    contract = workflow_contract(workflow)
    scoped_name = re.search(rb"matrix.profile == 'user-community-integration' && '([^']+)'", workflow)
    scoped_name = scoped_name.group(1).decode('utf-8') if scoped_name else None
    candidates = [job for job in jobs if job.get('name') in (contract['check'][0], scoped_name)]
    require(len(candidates) == 1, 'missing-or-duplicate-job')
    selected_profile = profile.POST_READS if scoped_name and candidates[0].get('name') == scoped_name else profile.FULL
    if selected_profile == profile.POST_READS:
        contract = workflow_contract(workflow, selected_profile)
    source_jobs = {}
    for key, (name, step_names) in contract.items():
        job, completed = one_job(jobs, name, now)
        require(job.get('run_id') == run_id and job.get('run_attempt') == attempt,
                'source-job-identity-differs')
        steps = job.get('steps')
        require(isinstance(steps, list) and all(isinstance(s, dict) for s in steps),
                'invalid-source-job-steps')
        names = [s.get('name') for s in steps]
        require(all(isinstance(name, str) and bool(name) for name in names) and
                len(names) == len(set(names)), 'duplicate-source-job-step')
        for required in step_names:
            found = [s for s in steps if s.get('name') == required]
            require(len(found) == 1 and found[0].get('status') == 'completed' and
                    found[0].get('conclusion') == 'success', 'missing-or-skipped-validation-step')
        source_jobs[key] = (job, completed)
    finished = source_jobs['ci-result'][1]
    require(now - MAX_AGE <= finished <= merged_at and
            all(value[1] <= finished for value in source_jobs.values()) and
            timestamp(run.get('created_at')) <= finished <= timestamp(run.get('updated_at')) <= now,
            'source-proof-expired-or-unordered')
    artifacts = pages(api, prefix + '/actions/runs/' + str(run_id) + '/artifacts', 'artifacts')
    validation_key = 'full' if selected_profile == profile.FULL else 'scoped'
    wanted = {
        'package': ('backend-app-package-' + str(run_id) + '-' + str(attempt), PACKAGE_MEMBERS),
        validation_key: ('backend-app-check-' + str(run_id) + '-' + str(attempt) + '-' + selected_profile, FULL_MEMBERS),
        'proof': ('backend-app-postmerge-proof-' + str(run_id) + '-' + str(attempt), ('postmerge-proof.txt',)),
    }
    contents, artifact_ids = {}, {}
    for key, (name, members) in wanted.items():
        matches = [item for item in artifacts if item.get('name') == name]
        require(len(matches) == 1, 'missing-or-duplicate-artifact')
        item = matches[0]
        origin = item.get('workflow_run')
        require(isinstance(origin, dict) and origin.get('id') == run_id and
                origin.get('head_sha') == head and origin.get('repository_id') == repository_id and
                origin.get('head_repository_id') == head_repository_id, 'artifact-run-differs')
        require(item.get('expired') is False and type(item.get('size_in_bytes')) is int and
                0 < item['size_in_bytes'] <= MAX_ARCHIVE and
                timestamp(item.get('expires_at')) > now and timestamp(item.get('created_at')) <= now,
                'artifact-unavailable')
        artifact_ids[key] = number(item.get('id'))
        archive = api.archive(item['id'])
        require(isinstance(archive, bytes) and len(archive) == item['size_in_bytes'],
                'artifact-archive-size-differs')
        require(isinstance(item.get('digest'), str) and
                re.fullmatch('sha256:[0-9a-f]{64}', item['digest']) and
                item['digest'] == 'sha256:' + hashlib.sha256(archive).hexdigest(),
                'artifact-archive-digest-differs')
        contents[key] = zip_members(archive, members)
    saved = document(contents['proof']['postmerge-proof.txt'])
    require(isinstance(saved, dict) and set(saved) == {'schema_version', 'status', 'mode', 'reason', 'identity'} and
            type(saved.get('schema_version')) is int and saved['schema_version'] == 1 and
            saved.get('status') == 'decided' and
            (saved.get('mode'), saved.get('reason')) in (
                ('full', 'pull-request-full'), ('execute', 'pull-request-validation')) and
            'source_proof' not in saved,
            'source-proof-was-not-executed-full')
    if selected_profile == profile.POST_READS:
        require((saved.get('mode'), saved.get('reason')) == ('execute', 'pull-request-validation'),
                'source-scoped-proof-was-not-executed')
    source = saved.get('identity')
    require(isinstance(source, dict) and source.get('repository') == profile.REPOSITORY and
            source.get('event_name') == 'pull_request' and source.get('before_sha') == before and
            source.get('tree_sha') == identity['tree_sha'] and source.get('run_id') == str(run_id) and
            source.get('run_attempt') == str(attempt), 'source-proof-identity-differs')
    source_sha = sha(source.get('source_sha'))
    require(source.get('workflow_ref') == profile.REPOSITORY + '/' + WORKFLOW +
            '@refs/pull/' + str(pr_number) + '/merge', 'source-workflow-ref-differs')
    workflow_sha = sha(source.get('workflow_sha'))
    commit = api.json(prefix + '/git/commits/' + source_sha)
    require(isinstance(commit, dict) and commit.get('sha') == source_sha and
            isinstance(commit.get('tree'), dict) and commit['tree'].get('sha') == identity['tree_sha'] and
            isinstance(commit.get('parents'), list) and len(commit['parents']) == 2 and
            all(isinstance(parent, dict) for parent in commit['parents']) and
            [parent.get('sha') for parent in commit['parents']] == [before, head],
            'synthetic-merge-differs')
    feature = api.json(prefix + '/git/commits/' + head)
    require(isinstance(feature, dict) and feature.get('sha') == head and
            isinstance(feature.get('tree'), dict) and feature['tree'].get('sha') == identity['tree_sha'],
            'feature-head-tree-differs')
    file = api.json(prefix + '/contents/' + WORKFLOW + '?ref=' + workflow_sha)
    require(isinstance(file, dict) and file.get('type') == 'file' and file.get('encoding') == 'base64' and
            type(file.get('size')) is int and 0 < file['size'] <= MAX_JSON and
            isinstance(file.get('content'), str), 'source-workflow-unavailable')
    try:
        raw = base64.b64decode(''.join(file['content'].split()), validate=True)
    except (ValueError, binascii.Error):
        raise Unavailable('source-workflow-unavailable') from None
    require(len(raw) == file['size'] and raw == workflow, 'source-workflow-differs')
    package, full = contents['package'], contents[validation_key]
    metadata(package['build/ci/runtime-metadata.txt'], source)
    metadata(full['build/ci/runtime-metadata.txt'], source)
    jar = jar_digest(package['build/ci/boot-jar.sha256'])
    require(jar_digest(full['build/ci/boot-jar.sha256']) == jar, 'source-jar-differs')
    plan = document(full['build/ci/contract-plan.txt'])
    execution = document(full['build/ci/contract-execution.txt'])
    paths = git('diff', '--name-only', '--no-renames', '-z', before, merged, '--').split(b'\0')
    try:
        paths = [p.decode('utf-8') for p in paths if p]
        expected_context = dict(event_name='pull_request', repository=profile.REPOSITORY,
            head_repository=profile.REPOSITORY, head_ref=head_ref, base_sha=before, head_sha=head,
            source_sha=source_sha, tree_sha=identity['tree_sha'], run_id=str(run_id), run_attempt=str(attempt))
        if selected_profile == profile.POST_READS:
            require(profile.verify_post_reads_inputs(git_reader=git) is True, 'source-scope-inputs-differ')
            expected_context['post_reads_scope_verified'] = True
        expected_plan = profile.make_plan(expected_context, paths, selected_profile, jar)
        require(isinstance(execution, dict) and plan == expected_plan,
                'source-full-plan-differs' if selected_profile == profile.FULL else 'source-scoped-plan-differs')
        expected_execution = profile.audit_execution(plan, expected_context, paths, selected_profile,
                                                     jar, execution.get('step_results'))
        require(profile._encoded(execution) == profile._encoded(expected_execution),
                'source-full-execution-differs' if selected_profile == profile.FULL else 'source-scoped-execution-differs')
    except (UnicodeError, profile.ProfileBlocked):
        raise Unavailable('source-full-execution-differs' if selected_profile == profile.FULL else 'source-scoped-execution-differs') from None
    latest = api.json(prefix + '/actions/runs/' + str(run_id))
    require(isinstance(latest, dict) and all(latest.get(k) == run.get(k) for k in
            ('id', 'run_attempt', 'event', 'head_sha', 'path', 'status', 'conclusion', 'workflow_id')),
            'source-run-changed')
    return dict(pr_number=pr_number, run_id=str(run_id), run_attempt=str(attempt), source_sha=source_sha,
                base_sha=before, head_sha=head, tree_sha=identity['tree_sha'],
                workflow_sha=workflow_sha, workflow_sha256=hashlib.sha256(workflow).hexdigest(),
                jar_sha256=jar, artifact_ids=artifact_ids, profile=selected_profile)


def decide(env, *, git_reader=git_read, github_reader=None, now=None):
    result = {'schema_version': 1, 'status': 'decided', 'mode': 'execute', 'reason': 'unavailable', 'identity': {}}
    try:
        identity, payload, workflow = context(env, git_reader)
        result['identity'] = identity
        if identity['event_name'] == 'pull_request':
            result['reason'] = 'pull-request-validation'
            return result  # API0, including fork PRs; ordinary full is never reduced.
        api = github_reader if github_reader is not None else GitHubReader(env.get('GH_TOKEN'))
        reference = verify(identity, payload, workflow, git_reader, api,
                           now if now is not None else dt.datetime.now(dt.timezone.utc))
        scoped = reference['profile'] == profile.POST_READS
        result.update(mode='reused-scoped' if scoped else 'reused-full',
                      reason='identical-tree-merged-pr-scoped' if scoped else 'identical-tree-merged-pr-full',
                      source_proof=reference)
    except Unavailable as failure:
        result['reason'] = str(failure)
    return result


def destination(path):
    require_path = Path.cwd() / OUTPUT
    path = Path(path)
    if not path.is_absolute():
        path = Path.cwd() / path
    if path != require_path or path.resolve() != path or path.is_symlink() or path.exists():
        raise ValueError('Invalid proof destination')
    return path


def write_proof(path, value):
    path = destination(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as output:
        output.write(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        destination(args.output)  # Invalid local destinations never trigger GitHub reads.
        result = decide(os.environ)
        write_proof(args.output, result)
    except Exception:
        print('Postmerge proof verification could not complete.', file=sys.stderr)
        return 1
    print('mode=' + result['mode'])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
