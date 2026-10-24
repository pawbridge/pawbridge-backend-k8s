#!/usr/bin/env python3
"""Fixed backend validation profiles and bounded evidence; no application or fixture code."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

FULL = 'full'
PILOT = 'f25-six-smoke-pilot'

# PostgreSQL compatibility contracts that every full run must execute besides the packaged smokes.
FULL_CONTRACTS = ('fixture_auth_sessions', 'pg_auth_sessions', 'restore_auth_sessions')

AUTH_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlAuthSessionCompatibilityTest'
AUTH_METHOD_CASES = (('givenCurrentClaimsAndAnActiveSession__whenRotating__thenIssueCurrentClaimsAndRecordTheRetiredHash', 1),
 ('givenInactiveOrUnknownTokens__whenRefreshing__thenRejectWithoutAnyMutation', 4),
 ('givenATwoGenerationsOldTokenAfterTheGraceWindow__whenRefreshing__thenRevokeOnlyThatSessionAndKeepTheRevocation', 1),
 ('givenALostRotationResponse__whenTheRetiredTokenReturnsAfter59Seconds__thenRotateAgainOnceAndRecordTheUnusedSuccessor', 1),
 ('givenTwoGraceRequestsWithTheSameRetiredToken__whenRefreshingConcurrently__thenOnlyOneRotatesAgain', 1),
 ('givenSixLoginsOfOneUser__whenCreatingSessions__thenKeepOnlyTheFiveMostRecentlyUsedActive', 1),
 ('givenLogoutRevokeAllOrCleanup__whenWriting__thenChangeOnlyTheTargetedSessions', 3),
 ('givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCalling__thenFailServerSideWithoutPartialMutationAndRecover', 4),
 ('givenADeletedUser__whenDeletingTheAccountRow__thenItsSessionsCascade', 1))
# Single-case methods are reported as name(), parameterized ones as name[index].
AUTH_REPORT_CASES = tuple(
    method + '()' if count == 1 else method + '[' + str(index) + ']'
    for method, count in AUTH_METHOD_CASES for index in range(1, count + 1))
AUTH_SEOUL_METHOD = 'givenCurrentClaimsAndAnActiveSession__whenRotating__thenIssueCurrentClaimsAndRecordTheRetiredHash'

REPOSITORY = 'pawbridge/pawbridge-backend-k8s'
PILOT_REF = 'ci/backend-contract-regression-scope'
PILOT_BASE = '0c1579a8ddb93cdea376be1ea57ac3b5c98bddf3'
PILOT_PATHS = frozenset({
    '.github/workflows/backend-app-check.yml',
    '.github/scripts/backend_contract_profile.py',
    '.github/scripts/test_backend_contract_profile.py',
    '.github/scripts/test_backend_app_ci_policy.py',
    'backend-app/README.md',
})
ALL_SMOKES = tuple('jar_f' + str(number) for number in range(3, 30)) + ('jar_f31',)
PILOT_SKIPS = tuple('jar_f' + str(number) for number in (16, 17, 19, 21, 22, 23))
IDENTITY_KEYS = ('event_name', 'repository', 'head_repository', 'head_ref',
                 'base_sha', 'head_sha', 'source_sha', 'tree_sha', 'run_id', 'run_attempt')


class ProfileBlocked(ValueError):
    """Only fixed non-sensitive messages may cross the CLI boundary."""


def _paths(paths):
    if (not isinstance(paths, (list, tuple))
            or any(not isinstance(path, str) or not path for path in paths)
            or len(set(paths)) != len(paths)):
        raise ProfileBlocked('Changed paths are invalid or duplicated.')
    return sorted(paths)


def verify_auth_sessions_report(utc_directory, seoul_directory):
    """Require every auth session method/index label and only the designated Seoul case."""
    for folder, expected in ((utc_directory, AUTH_REPORT_CASES),
                             (seoul_directory, (AUTH_SEOUL_METHOD + '()',))):
        try:
            directory = Path(folder).absolute()
            reports = list(directory.glob('TEST-*.xml'))
            if (directory.is_symlink() or directory.resolve() != directory or len(reports) != 1
                    or reports[0].is_symlink() or reports[0].resolve() != reports[0]
                    or reports[0].name != 'TEST-' + AUTH_REPORT_CLASS + '.xml'):
                raise ProfileBlocked('The auth session report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != AUTH_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
                    or len(suite.findall('testcase')) != len(expected)
                    or len(set(names)) != len(expected) or set(names) != set(expected)
                    or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != AUTH_REPORT_CLASS
                           or case.attrib.get('status', 'run') not in ('run', 'passed')
                           or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                           for case in cases)):
                raise ProfileBlocked('The auth session cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The auth session report is invalid.') from None


def select_profiles(context, paths):
    """Unknown/fork/new-base changes stay full; explicit ineligible pilot is refused."""
    paths = _paths(paths)
    if not isinstance(context, dict):
        raise ProfileBlocked('Profile context is invalid.')
    requested = context.get('requested_profile')
    if requested not in (None, '', FULL, PILOT):
        raise ProfileBlocked('Requested profile is unknown.')
    if requested == FULL:
        return [FULL]
    eligible = (context.get('event_name') == 'pull_request'
                and context.get('repository') == REPOSITORY
                and context.get('head_repository') == REPOSITORY
                and context.get('head_ref') == PILOT_REF
                and context.get('base_sha') == PILOT_BASE
                and bool(paths) and set(paths).issubset(PILOT_PATHS))
    if requested == PILOT and not eligible:
        raise ProfileBlocked('The requested pilot does not match its fixed scope.')
    if eligible:
        return [FULL, PILOT]
    return [FULL]


def expected_smokes(profile):
    if profile == FULL:
        return ALL_SMOKES
    if profile == PILOT:
        return tuple(step for step in ALL_SMOKES if step not in PILOT_SKIPS)
    raise ProfileBlocked('Execution profile is unknown.')


def _identity(context):
    try:
        identity = {key: context[key] for key in IDENTITY_KEYS}
    except (KeyError, TypeError):
        raise ProfileBlocked('Candidate identity is incomplete.') from None
    if any(not isinstance(value, str) or not value for value in identity.values()):
        raise ProfileBlocked('Candidate identity contains invalid values.')
    if identity['event_name'] not in ('pull_request', 'push'):
        raise ProfileBlocked('Candidate event is unsupported.')
    for key in ('base_sha', 'head_sha', 'source_sha', 'tree_sha'):
        value = identity[key]
        if (not re.fullmatch(r'[0-9a-f]{40}', value)
                or (value == '0' * 40 and not (key == 'base_sha' and identity['event_name'] == 'push'))):
            raise ProfileBlocked('Candidate commit or tree identity is invalid.')
    for key in ('repository', 'head_repository'):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', identity[key]):
            raise ProfileBlocked('Candidate repository identity is invalid.')
    if (any(ord(char) < 32 for char in identity['head_ref'])
            or any(not re.fullmatch(r'[1-9][0-9]*', identity[key]) for key in ('run_id', 'run_attempt'))):
        raise ProfileBlocked('Candidate ref or run identity is invalid.')
    return identity


def _encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def make_plan(context, paths, profile, jar_sha256):
    required = expected_smokes(profile)
    identity = _identity(context)
    paths = _paths(paths)
    selection = dict(context, requested_profile=profile)
    if profile not in select_profiles(selection, paths):
        raise ProfileBlocked('Execution profile is not eligible.')
    if not isinstance(jar_sha256, str) or not re.fullmatch(r'[0-9a-f]{64}', jar_sha256):
        raise ProfileBlocked('The executable JAR hash is invalid.')
    result = {
        'schema_version': 1, 'status': 'planned', 'profile': profile,
        'identity': identity, 'jar_sha256': jar_sha256,
        'changed_paths_sha256': hashlib.sha256(_encoded(paths)).hexdigest(),
        'selection_reason': 'fixed-f25-ci-only-pilot' if profile == PILOT else 'full-reference-or-default',
        'required_smokes': list(required),
        'skipped_smokes': [step for step in ALL_SMOKES if step not in required],
    }
    if profile == FULL:
        result['required_contracts'] = list(FULL_CONTRACTS)
    return result


def audit_execution(plan, context, paths, profile, jar_sha256, steps):
    """Validate both raw outcomes and conclusions; never persist arbitrary step outputs."""
    expected = make_plan(context, paths, profile, jar_sha256)
    if not isinstance(plan, dict) or _encoded(plan) != _encoded(expected):
        raise ProfileBlocked('The execution plan differs from the current candidate or profile.')
    if (not isinstance(steps, dict) or any(not isinstance(key, str) for key in steps)
            or {key for key in steps if key.startswith('jar_f')} != set(ALL_SMOKES)):
        raise ProfileBlocked('Packaged step identities are missing, unknown or duplicated.')
    results = {}
    required = set(expected['required_smokes'])
    contracts = tuple(expected.get('required_contracts', ()))
    skipped_contracts = tuple(expected.get('skipped_contracts', ()))
    for key in ALL_SMOKES + contracts + skipped_contracts + ('owned_cleanup',):
        step = steps.get(key)
        wanted = 'success' if key == 'owned_cleanup' or key in required or key in contracts else 'skipped'
        if (not isinstance(step, dict) or step.get('outcome') != wanted
                or step.get('conclusion') != wanted):
            raise ProfileBlocked('A required execution, planned skip or owned cleanup differs.')
        results[key] = {'outcome': wanted, 'conclusion': wanted}
    result = {
        'schema_version': 1, 'status': 'passed', 'profile': profile,
        'identity': expected['identity'], 'jar_sha256': jar_sha256,
        'changed_paths_sha256': expected['changed_paths_sha256'],
        'plan_sha256': hashlib.sha256(_encoded(expected)).hexdigest(),
        'required_smokes': expected['required_smokes'],
        'executed_smokes': expected['required_smokes'],
        'skipped_smokes': expected['skipped_smokes'], 'step_results': results,
    }
    if contracts:
        result['required_contracts'] = list(contracts)
    if skipped_contracts:
        result['skipped_contracts'] = list(skipped_contracts)
    return result


def load_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProfileBlocked('JSON evidence repeats a key.')
            result[key] = value
        return result

    def invalid_constant(_value):
        raise ProfileBlocked('JSON evidence contains a non-finite value.')

    try:
        return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (TypeError, ValueError):
        raise ProfileBlocked('JSON evidence is malformed or duplicated.') from None


def runtime_context():
    """Reuse the existing event/source/diff policy; do not duplicate its Git checks."""
    env = os.environ
    try:
        import ci_branch_policy as policy

        payload = load_json(Path(env['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        event = policy.validate_event(env)
        paths = policy.changed_paths(env, event)
        repository = env['GITHUB_REPOSITORY']
        source = env['GITHUB_SHA']
        if event == 'pull_request':
            pull = payload['pull_request']
            head = pull['head']
            base = pull['base']['sha']
            if base != env.get('PULL_REQUEST_BASE_SHA'):
                raise ProfileBlocked('Event base differs from the verified change comparison.')
            head_repo, head_ref, head_sha = head['repo']['full_name'], head['ref'], head['sha']
        else:
            base = payload['before']
            if base != env.get('PUSH_BEFORE_SHA'):
                raise ProfileBlocked('Push base differs from the verified change comparison.')
            head_repo, head_ref, head_sha = repository, env['GITHUB_REF'].removeprefix('refs/heads/'), source
        context = dict(event_name=event, repository=repository, head_repository=head_repo,
                       head_ref=head_ref, base_sha=base, head_sha=head_sha, source_sha=source,
                       tree_sha=policy.git('rev-parse', 'HEAD^{tree}').decode('ascii').strip(),
                       run_id=env['GITHUB_RUN_ID'], run_attempt=env['GITHUB_RUN_ATTEMPT'])
        return _identity(context), _paths(paths)
    except (ImportError, KeyError, TypeError, AttributeError, OSError, UnicodeError, ValueError):
        raise ProfileBlocked('The verified runtime context could not be constructed.') from None


def _jar_hash(path):
    if path.is_symlink() or not path.is_file():
        raise ProfileBlocked('The executable JAR is missing or redirected.')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _write_evidence(path, expected_name, value):
    if path.name != expected_name or path.is_symlink():
        raise ProfileBlocked('The evidence destination is invalid or redirected.')
    # Exclusive creation preserves the first result and rejects duplicate evidence.
    with path.open('x', encoding='utf-8') as output:
        output.write(_encoded(value).decode('ascii') + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('plan', 'audit'), required=True)
    parser.add_argument('--profile', choices=(FULL, PILOT), required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--execution', type=Path)
    args = parser.parse_args(argv)
    context = None
    jar_hash = None
    try:
        context, paths = runtime_context()
        jar_hash = _jar_hash(args.jar)
        if args.mode == 'plan':
            _write_evidence(args.plan, 'contract-plan.txt', make_plan(context, paths, args.profile, jar_hash))
        else:
            if args.execution is None or args.execution.parent.resolve() != args.plan.parent.resolve():
                raise ProfileBlocked('Execution evidence must share the plan directory.')
            if args.plan.is_symlink() or not args.plan.is_file():
                raise ProfileBlocked('The execution plan is missing or redirected.')
            plan = load_json(args.plan.read_text(encoding='utf-8'))
            steps = load_json(os.environ.get('CONTRACT_STEPS_JSON', ''))
            result = audit_execution(plan, context, paths, args.profile, jar_hash, steps)
            _write_evidence(args.execution, 'contract-execution.txt', result)
    except (ProfileBlocked, OSError, UnicodeError):
        if args.mode == 'audit' and args.execution is not None:
            failed = {'schema_version': 1, 'status': 'failed', 'profile': args.profile}
            if context is not None:
                failed['identity'] = context
            if jar_hash is not None:
                failed['jar_sha256'] = jar_hash
            try:
                _write_evidence(args.execution, 'contract-execution.txt', failed)
            except (ProfileBlocked, OSError):
                pass  # Never replace earlier evidence or hide the original failed exit status.
        print('Contract profile verification failed.', file=sys.stderr)
        return 1
    print('Contract profile ' + args.mode + ' verified.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
