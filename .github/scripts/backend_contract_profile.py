#!/usr/bin/env python3
"""Fixed backend validation profiles and bounded evidence; no application or fixture code."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

FULL = 'full'
PILOT = 'f25-six-smoke-pilot'

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
# F17 through F29 (private notes, administrator count, block list, stream, send) run as Spring contract tests in
# the `contract` job; their fixture preparation steps stay in the workflow until the final packaged-JAR cleanup.
ALL_SMOKES = tuple('jar_f' + str(number) for number in range(3, 17)) + ('jar_f31',)
PILOT_SKIPS = tuple('jar_f' + str(number) for number in (16,))
# The packaged smokes run as parallel matrix shards. Each shard is a fresh runner that re-provisions
# the fixture from F2 up to its own last stage, then runs only its smokes in the original order.
# Balanced by measured step time: the later shards pay for more fixture preparation.
SHARDS = {
    1: tuple('jar_f' + str(number) for number in range(3, 17)),
    4: ('jar_f31',),
}
# Smokes that leave fixture state a later step reads, mapped to the workflow step that reproduces
# exactly that state in shards that skip the smoke. The F4 smoke creates the two support tables
# that later business snapshots select from; no other smoke leaves state a later step reads.
STATE_STEPS = {'jar_f4': 'shape_f4'}
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


def expected_smokes(profile, shard=None):
    """The profile's smokes, limited to one shard when given; None keeps the whole unsharded set."""
    if profile == FULL:
        smokes = ALL_SMOKES
    elif profile == PILOT:
        smokes = tuple(step for step in ALL_SMOKES if step not in PILOT_SKIPS)
    else:
        raise ProfileBlocked('Execution profile is unknown.')
    if shard is None:
        return smokes
    if type(shard) is not int or shard not in SHARDS:
        raise ProfileBlocked('Execution shard is unknown.')
    return tuple(step for step in smokes if step in SHARDS[shard])


def shard_of(smoke):
    for shard, smokes in SHARDS.items():
        if smoke in smokes:
            return shard
    raise ProfileBlocked('Packaged step is not assigned to a shard.')


def state_steps(shard):
    """(reproduced, skipped) state steps: reproduce only where an earlier shard owns the smoke."""
    reproduced = [step for smoke, step in STATE_STEPS.items() if shard > shard_of(smoke)]
    skipped = [step for smoke, step in STATE_STEPS.items() if shard <= shard_of(smoke)]
    return reproduced, skipped


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


def make_plan(context, paths, profile, jar_sha256, shard=None):
    required = expected_smokes(profile, shard)
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
    if shard is not None:
        result['shard'] = shard
        result['reproduced_state'], result['skipped_state'] = state_steps(shard)
    return result


def audit_execution(plan, context, paths, profile, jar_sha256, steps, shard=None):
    """Validate both raw outcomes and conclusions; never persist arbitrary step outputs."""
    expected = make_plan(context, paths, profile, jar_sha256, shard)
    if not isinstance(plan, dict) or _encoded(plan) != _encoded(expected):
        raise ProfileBlocked('The execution plan differs from the current candidate or profile.')
    if (not isinstance(steps, dict) or any(not isinstance(key, str) for key in steps)
            or {key for key in steps if key.startswith('jar_f')} != set(ALL_SMOKES)):
        raise ProfileBlocked('Packaged step identities are missing, unknown or duplicated.')
    results = {}
    required = set(expected['required_smokes'])
    reproduced = tuple(expected.get('reproduced_state', ()))
    skipped_state = tuple(expected.get('skipped_state', ()))
    succeeded = required | set(reproduced) | {'owned_cleanup'}
    checked = ALL_SMOKES + reproduced + skipped_state + ('owned_cleanup',)
    for key in checked:
        step = steps.get(key)
        wanted = 'success' if key in succeeded else 'skipped'
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
    if shard is not None:
        result['shard'] = shard
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
    parser.add_argument('--shard', type=int, choices=sorted(SHARDS))
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
            plan = make_plan(context, paths, args.profile, jar_hash, args.shard)
            _write_evidence(args.plan, 'contract-plan.txt', plan)
        else:
            if args.execution is None or args.execution.parent.resolve() != args.plan.parent.resolve():
                raise ProfileBlocked('Execution evidence must share the plan directory.')
            if args.plan.is_symlink() or not args.plan.is_file():
                raise ProfileBlocked('The execution plan is missing or redirected.')
            plan = load_json(args.plan.read_text(encoding='utf-8'))
            steps = load_json(os.environ.get('CONTRACT_STEPS_JSON', ''))
            result = audit_execution(plan, context, paths, args.profile, jar_hash, steps, args.shard)
            _write_evidence(args.execution, 'contract-execution.txt', result)
    except (ProfileBlocked, OSError, UnicodeError):
        if args.mode == 'audit' and args.execution is not None:
            failed = {'schema_version': 1, 'status': 'failed', 'profile': args.profile}
            if args.shard is not None:
                failed['shard'] = args.shard
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
