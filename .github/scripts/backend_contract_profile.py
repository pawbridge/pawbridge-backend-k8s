#!/usr/bin/env python3
"""Fixed F25 pilot selection and bounded evidence; no application or fixture code."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


FULL = 'full'
PILOT = 'f25-six-smoke-pilot'
NOTE_EXPIRY = 'note-expiry'
NOTE_BASE = 'a35ee31ba700093c97fa9c0864d6a8677e5fae78'
NOTE_APP_EXCEPTIONS = frozenset({
    'backend-app/README.md', 'backend-app/scripts/f4_ci.py',
    'backend-app/scripts/test_f4_ci_startup.py',
})
NOTE_HELPER_HASHES = {
    'backend-app/scripts/f4_ci.py': '68b4df20c29cd7d232d33ff1983c51a44c06302642e6d23d30474cd5e7babfa0',
    'backend-app/scripts/test_f4_ci_startup.py': 'a87a12457e2be5cf291c634345563a74b1d1b3cf658ab7f8dca4a5a03b2bca90',
}
NOTE_ALLOWED_PATHS = frozenset({
    '.github/workflows/backend-app-check.yml',
    '.github/scripts/backend_contract_profile.py',
    '.github/scripts/test_backend_contract_profile.py',
    '.github/scripts/test_backend_note_expiry_profile.py',
    '.github/scripts/test_backend_app_ci_policy.py',
    '.github/scripts/backend_postmerge_proof.py',
    '.github/scripts/test_backend_postmerge_proof.py',
    'backend-app/README.md', 'backend-app/boot/build.gradle',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F29LegacyNoteSendOracle.java',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlNoteSendCompatibilityTest.java',
    'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java',
    'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/NoteExpiryConfiguration.java',
    'backend-app/boot/src/test/java/com/pawbridge/backend/NoteExpiryConfigurationTest.java',
    'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/application/NoteExpiry.java',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcNoteExpiry.java',
    'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcNoteExpiryTest.java',
    'backend-app/scripts/prepare_f29_legacy_oracle.py',
}) | NOTE_APP_EXCEPTIONS
NOTE_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlNoteSendCompatibilityTest'
NOTE_METHODS = (
    'givenOwnedExpiryAndFortyEightHourBoundaries__whenBothExpireTwice__thenKeepForeignBaselineAndApplyFkThenOrphanCleanupEvenWhenFirstDeleteIsZero',
    'givenThreeHundredAndOneOwnedCandidatesPerTable__whenOriginalAndCandidateExpireOneBatch__thenCharacterizeOriginalAndRequireCandidateOldestThreeHundred',
    'givenOneLockedOwnedNoteAndOrphanRequest__whenBothExpire__thenSkipThoseRowsAndDeleteTheUnlockedOwnedCandidates',
    'givenOwnedRequestDeletePermissionFailure__whenCandidateHasAlreadyDeletedTheExpiredNote__thenRollbackBothSqlAndRestoreOnlyOwnedPrivileges',
    'givenAnOwnedDeferredDeleteConstraint__whenBothCommitExpiry__thenRejectFromThatConstraintAndRollbackAllEighteenTableChanges',
    'givenAnActiveExpiryWaitingForOwnedMailboxFk__whenActualBootSchedulerContextCloses__thenReturnTheTransactionBeforeWriterPoolDestructionAndNeverReschedule',
)
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
ALL_SMOKES = tuple('jar_f' + str(number) for number in range(3, 30))
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


def verify_note_expiry_inputs(git_reader=None):
    """Pin every application/build/test input to the already inspected F30 source.

    Only README and the separately reviewed F4 shape bootstrap may differ. CI
    policy itself remains a reviewed source change, never proof of its own safety.
    """
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        raw = git('diff', '--name-only', '--no-renames', '-z', NOTE_BASE, 'HEAD', '--', 'backend-app')
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(NOTE_APP_EXCEPTIONS):
            return False
        return all(hashlib.sha256(git('show', 'HEAD:' + path)).hexdigest() == digest
                   for path, digest in NOTE_HELPER_HASHES.items())
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_note_expiry_report(directory):
    try:
        directory = Path(directory)
        reports = list(directory.glob('TEST-*.xml'))
        if directory.is_symlink() or len(reports) != 1 or reports[0].is_symlink():
            raise ProfileBlocked('The scoped PostgreSQL report is missing or redirected.')
        suite = ET.parse(reports[0]).getroot()
        cases = list(suite.iter('testcase'))
        names = [case.attrib.get('name', '').removesuffix('()') for case in cases]
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != NOTE_REPORT_CLASS
                or int(suite.attrib.get('tests', -1)) != len(cases) or len(cases) != 6
                or len(set(names)) != 6 or set(names) != set(NOTE_METHODS)
                or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                       for key in ('failures', 'errors', 'skipped', 'disabled'))
                or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                or any(case.attrib.get('classname') != NOTE_REPORT_CLASS or
                       case.attrib.get('status', 'run') not in ('run', 'passed') for case in cases)):
            raise ProfileBlocked('The scoped PostgreSQL cases did not all execute successfully.')
    except (OSError, ValueError, TypeError, ET.ParseError):
        raise ProfileBlocked('The scoped PostgreSQL report is invalid.') from None


def select_profiles(context, paths):
    """Unknown/fork/new-base changes stay full; explicit ineligible pilot is refused."""
    paths = _paths(paths)
    if not isinstance(context, dict):
        raise ProfileBlocked('Profile context is invalid.')
    requested = context.get('requested_profile')
    if requested not in (None, '', FULL, PILOT, NOTE_EXPIRY):
        raise ProfileBlocked('Requested profile is unknown.')
    if requested == FULL:
        return [FULL]
    scoped = (context.get('event_name') in ('pull_request', 'push')
              and context.get('repository') == REPOSITORY
              and context.get('head_repository') == REPOSITORY
              and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
              and context.get('note_expiry_scope_verified') is True
              and bool(paths) and set(paths).issubset(NOTE_ALLOWED_PATHS))
    if requested == NOTE_EXPIRY and not scoped:
        raise ProfileBlocked('The requested note expiry scope differs from its reviewed inputs.')
    if scoped and requested in (None, '', NOTE_EXPIRY):
        return [NOTE_EXPIRY]
    eligible = (context.get('event_name') == 'pull_request'
                and context.get('repository') == REPOSITORY
                and context.get('head_repository') == REPOSITORY
                and context.get('head_ref') == PILOT_REF
                and context.get('base_sha') == PILOT_BASE
                and bool(paths) and set(paths).issubset(PILOT_PATHS))
    if requested == PILOT and not eligible:
        raise ProfileBlocked('The requested pilot does not match its fixed scope.')
    if eligible and requested != FULL:
        return [FULL, PILOT]
    return [FULL]


def expected_smokes(profile):
    if profile == NOTE_EXPIRY:
        return ()
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
        'selection_reason': ('fixed-f30-inputs-and-scoped-postgresql' if profile == NOTE_EXPIRY else
                             'fixed-f25-ci-only-pilot' if profile == PILOT else 'full-reference-or-default'),
        'required_smokes': list(required),
        'skipped_smokes': [step for step in ALL_SMOKES if step not in required],
    }
    if profile == NOTE_EXPIRY:
        result['required_contracts'] = ['fixture_f4_shape', 'pg_note_expiry']
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
    for key in ALL_SMOKES + contracts + ('owned_cleanup',):
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
        identity = _identity(context)
        identity['note_expiry_scope_verified'] = verify_note_expiry_inputs()
        return identity, _paths(paths)
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
    parser.add_argument('--profile', choices=(FULL, PILOT, NOTE_EXPIRY), required=True)
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
