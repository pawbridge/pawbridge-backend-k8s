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

# Database contract tests (backend-app/boot/src/contractTest) run in their own `contract` job on dev pushes.
# A complete run reports exactly these cases: (class, ((test label, invocations), ...)). A test label is the
# method name; a single-case method is reported as label(), a parameterized one as label(String)[index].
CONTRACT_PACKAGE = 'com.pawbridge.backend.contract'
AUTH_ROTATION = '활성 세션일 때__리프레시하면__현재 클레임을 주고 퇴역 해시를 남긴다'
CONTRACT_CASES = (
    ('AuthSessionContractTest', (
        (AUTH_ROTATION, 1),
        ('비활성이거나 모르는 토큰일 때__리프레시하면__변경 없이 거부한다', 4),
        ('유예 이후 두 세대 전 토큰일 때__리프레시하면__그 세션만 폐기하고 폐기를 유지한다', 1),
        ('회전 응답을 잃었을 때__59초 뒤 퇴역 토큰이 오면__한 번만 다시 회전하고 후계를 남긴다', 1),
        ('같은 퇴역 토큰의 유예 요청 둘일 때__동시에 리프레시하면__한 요청만 다시 회전한다', 1),
        ('한 사용자가 여섯 번 로그인할 때__세션을 만들면__최근 사용한 다섯 개만 활성으로 둔다', 1),
        ('로그아웃 전체 폐기 정리일 때__쓰면__대상 세션만 바꾼다', 3),
        ('필요한 쓰기 권한 하나를 회수했을 때__호출하면__부분 변경 없이 서버 오류이고 복구된다', 4),
        ('계정 행을 삭제했을 때__그 사용자의 세션을 조회하면__함께 연쇄 삭제되어 있다', 1),
        ('허용된 Origin의 쿠키 요청일 때__HTTP로 리프레시하면__새 쿠키를 주고 세션을 회전한다', 1),
    )),
    ('ContractInfrastructureTest', (
        ('마이그레이션을 적용했을 때__테이블을 조회하면__두 스키마의 현재 테이블만 있다', 1),
        ('앱 역할일 때__권한을 조회하면__슈퍼유저도 테이블 소유자도 아니다', 1),
        ('열 권한을 회수했을 때__앱 역할로 조회하면__거부되고 끝나면 복구된다', 1),
        ('본문이 실패했을 때__회수를 감싸면__예외를 그대로 던지고 권한은 복구된다', 1),
    )),
    ('SchemaSourcePinTest', (
        ('사용자 서비스 마이그레이션이 있을 때__복사본을 비교하면__해시가 원본과 같다', 1),
        ('커뮤니티 서비스 마이그레이션이 있을 때__복사본을 비교하면__해시가 원본과 같다', 1),
    )),
)
# Only the tests tagged timezone-sensitive run a second time with the JVM in Asia/Seoul.
CONTRACT_SEOUL_CASES = (('AuthSessionContractTest', ((AUTH_ROTATION, 1),)),)

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
# The packaged smokes run as parallel matrix shards. Each shard is a fresh runner that re-provisions
# the fixture from F2 up to its own last stage, then runs only its smokes in the original order.
# Balanced by measured step time: the later shards pay for more fixture preparation.
SHARDS = {
    1: tuple('jar_f' + str(number) for number in range(3, 18)),
    2: tuple('jar_f' + str(number) for number in range(18, 23)),
    3: tuple('jar_f' + str(number) for number in range(23, 28)),
    4: ('jar_f28', 'jar_f29', 'jar_f31'),
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


def _case_names(label, count):
    if count == 1:
        return [label + '()']
    return [label + '(String)[' + str(index) + ']' for index in range(1, count + 1)]


def verify_contract_reports(utc_directory, seoul_directory):
    """Require every pinned contract case in the UTC run and only the time-zone cases in the Seoul run."""
    for folder, expected in ((utc_directory, CONTRACT_CASES), (seoul_directory, CONTRACT_SEOUL_CASES)):
        try:
            directory = Path(folder).absolute()
            if directory.is_symlink() or directory.resolve() != directory:
                raise ProfileBlocked('The contract report directory is missing or redirected.')
            reports = sorted(directory.glob('TEST-*.xml'))
            wanted = sorted(directory / ('TEST-' + CONTRACT_PACKAGE + '.' + name + '.xml') for name, _ in expected)
            if reports != wanted or any(path.is_symlink() or path.resolve() != path for path in reports):
                raise ProfileBlocked('The contract report files are missing, extra or redirected.')
            for name, cases in expected:
                _verify_report_file(directory / ('TEST-' + CONTRACT_PACKAGE + '.' + name + '.xml'),
                                    CONTRACT_PACKAGE + '.' + name,
                                    [case for label, count in cases for case in _case_names(label, count)])
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The contract report is invalid.') from None


def _verify_report_file(path, class_name, expected):
    suite = ET.parse(path).getroot()
    cases = list(suite.iter('testcase'))
    names = [case.attrib.get('name', '') for case in cases]
    if (suite.tag != 'testsuite' or suite.attrib.get('name') != class_name
            or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
            or len(suite.findall('testcase')) != len(expected)
            or len(set(names)) != len(expected) or set(names) != set(expected)
            or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
            or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                   for key in ('failures', 'errors', 'skipped', 'disabled'))
            or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
            or any(case.attrib.get('classname') != class_name
                   or case.attrib.get('status', 'run') not in ('run', 'passed')
                   or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                   for case in cases)):
        raise ProfileBlocked('The contract cases did not all execute successfully.')


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
