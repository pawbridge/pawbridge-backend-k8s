#!/usr/bin/env python3
"""Hosted-only outgoing block status; reuse F17 tables, owner and exact ACL unchanged."""
import base64
import json
import os
from pathlib import Path
import stat
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f17_ci as f17

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
TASK = 'blockedAuthorsCompatibilityTest'
CLASSES = ('com.pawbridge.backend.compatibility.PostgresqlBlockedAuthorsCompatibilityTest',
           'com.pawbridge.backend.compatibility.F18LegacyBlockedAuthorsMvcTest')
EXPECTED_TESTS = dict(zip(CLASSES, (7, 14)))
CALLER, FIRST, SECOND, REVERSE = 9972001, 9972002, 9972003, 9972004
IDS = (CALLER, FIRST, SECOND, REVERSE)
BLOCKS = ((CALLER, FIRST), (CALLER, SECOND), (REVERSE, CALLER))
PATH = '/api/v1/notes/blocks/status'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
INVALID_MEMBERS = '작성자 식별자는 1~100개의 양수여야 합니다.'
INVALID_BINDING = '요청 형식과 로그인 정보를 확인해 주세요.'
MISSING = "필수 요청 파라미터 'memberIds'(이)가 누락되었습니다."
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
require_fixture = f17.require_fixture
require_owner = f17.require_owner
database_snapshot = f17.database_snapshot


def verify_results():
    f2.require_hosted()
    directory = ROOT / 'boot/build/test-results' / TASK
    if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in CLASSES}:
        raise SystemExit('F18 compatibility requires its exact two-class XML set')
    counts = {}
    for name in CLASSES:
        try:
            suite = ET.parse(directory / ('TEST-' + name + '.xml')).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F18 compatibility report missing or malformed') from None
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != name or values['tests'] <= 0
                or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or values['tests'] != EXPECTED_TESTS[name] or len(cases) != values['tests']
                or any(c.attrib.get('classname') != name for c in cases)
                or any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped'))):
            raise SystemExit('F18 compatibility requires executed successful unskipped cases')
        counts[name] = values['tests']
    print('Verified F18 single UTC PG and original MVC reports: ' + json.dumps(counts, sort_keys=True))
    return counts


def prepare():
    f2.require_hosted()
    owner = require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    proof = 'owner=' + owner + '\nfixture=F17 unchanged\nnew_business_ddl=0\nnew_acl=0\nnew_marker=0\n'
    target = EVIDENCE / 'f18-fixture-reuse.txt'
    if target.exists() and target.read_text() != proof:
        raise SystemExit('Refusing differing F18 fixture reuse evidence')
    target.write_text(proof)
    print('Reused the exact F17 fixture/owner/ACL; no F18 schema, grants or marker added')


def prepare_business_rows():
    require_fixture()
    if f3.sql('SELECT count(*) FROM pawbridge_user.users WHERE user_id IN ('
              + ','.join(str(identity) for identity in IDS[1:]) + ')') != '0':
        raise SystemExit('F18 target members must not have pre-existing User rows')
    members = ','.join('(' + str(identity) + ')' for identity in IDS)
    blocks = ','.join('(' + str(first) + ',' + str(second) + ",TIMESTAMPTZ '2026-10-19 00:00:00+00')" for first, second in BLOCKS)
    # One transaction records only successful fresh inserts. No target User or pending row is needed.
    f3.sql("BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES ("
           + str(CALLER) + ",'F18표시회원','f18-caller@fixture.invalid','F18 합성회원','GOOGLE','ROLE_USER'); "
           'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES ' + members + '; '
           'INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES ' + blocks + '; COMMIT;')
    return IDS


def cleanup_business_rows(owned):
    if tuple(owned) != IDS:
        raise ValueError('F18 cleanup requires the exact successfully inserted caller/member keys')
    block_keys = ','.join('(' + str(first) + ',' + str(second) + ')' for first, second in BLOCKS)
    for query in ('DELETE FROM pawbridge_community.private_note_blocks WHERE (member_id,blocked_id) IN (' + block_keys + ')',
                  'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + ','.join(str(i) for i in owned) + ')',
                  'DELETE FROM pawbridge_user.users WHERE user_id=' + str(CALLER) + " AND email='f18-caller@fixture.invalid'"):
        require_owner()
        f3.sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.resolve() != expected or path.is_symlink():
        raise SystemExit('F18 token file must belong to this hosted run without a symlink')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F18 synthetic token file must remain a regular 0600 file')
        tokens = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('F18 synthetic token file is missing or malformed') from None
    if not isinstance(tokens, dict) or not {'F18_CALLER', 'expired'}.issubset(tokens):
        raise SystemExit('F18 requires the refreshed real-issuer principal and expired token map')
    now = time.time()
    for key, identity in (('F18_CALLER', CALLER), ('expired', CALLER)):
        try:
            raw = tokens[key].split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
            valid = (type(claims['userId']) is int and claims['userId'] == identity and claims['role'] == 'ROLE_USER'
                     and type(claims['exp']) in (int, float)
                     and (claims['exp'] < now if key == 'expired' else claims['exp'] > now + 60))
        except (AttributeError, KeyError, IndexError, ValueError, TypeError):
            valid = False
        if not valid:
            raise SystemExit('F18 used token identity or final freshness differs from the fixture contract')
    return {key: tokens[key] for key in ('F18_CALLER', 'expired')}


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file(): raise SystemExit('Packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values(): print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'pool_maximum': 4,
              'blocked_authors_get_checks': 0, 'successful_checks': 0, 'rejected_checks': 0, 'nonempty_checks': 0,
              'empty_checks': 0, 'missing_parameter_checks': 0, 'invalid_parameter_checks': 0,
              'member_unavailable_checks': 0, 'storage_unavailable_checks': 0,
              'authentication_checks': 0, 'denied_route_checks': 0, 'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f18-packaged-startup.log', EVIDENCE / 'f18-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''
    default = {'memberIds': ','.join(str(i) for i in (SECOND, FIRST, REVERSE, CALLER, FIRST))}

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(params=None, values=(FIRST, SECOND), token='F18_CALLER', status=200, message=SUCCESS,
              layer='business', kind=None, method='GET', path=PATH):
        headers = {'X-User-Id': str(REVERSE), 'X-User-Role': 'ROLE_ADMIN', 'Content-Type': 'application/json'}
        if token is not None: headers['Authorization'] = 'Bearer ' + tokens.get(token, token)
        query = urllib.parse.urlencode(default if params is None else params, doseq=True)
        before = snapshot()
        actual, raw, received = f3.request(base, path + ('?' + query if query else ''), method=method, headers=headers)
        if snapshot() != before:
            raise AssertionError('Block status GET changed a business row or contacted S3')
        if actual != status or received.get('Set-Cookie'):
            raise AssertionError('Block status request lost its expected status or created a cookie')
        response = json.loads(raw)
        if layer in ('business', 'missing') and received.get('Cache-Control') != 'no-store':
            raise AssertionError('Block status success/binding/service responses must carry no-store')
        expected = {'code': status, 'message': message, 'data': None} if status == 200 or layer != 'business' else {'message': message}
        if status == 200: expected['data'] = {'memberIds': list(values)}
        if (response != expected or (status == 200 and (not isinstance(response['data']['memberIds'], list)
                or any(type(identity) is not int for identity in response['data']['memberIds'])))):
            raise AssertionError('Block status lost its exact ID-list field or original mixed error body')
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F18 request left an active DB transaction')
        if layer == 'route': result['denied_route_checks'] += 1
        else:
            result['blocked_authors_get_checks'] += 1
            result['successful_checks' if status == 200 else 'rejected_checks'] += 1
            if status == 200: result['nonempty_checks' if values else 'empty_checks'] += 1
        if kind: result[kind + '_checks'] += 1

    def denied_select(relation, column, message, kind, invalid_first=False):
        require_fixture()
        f3.sql('REVOKE SELECT(' + column + ') ON ' + relation + ' FROM pawbridge_f2_reader')
        try:
            if invalid_first:
                check({'memberIds': '0'}, status=400, message=INVALID_MEMBERS, kind='invalid_parameter')
            check(status=503, message=message, kind=kind)
        finally:
            require_owner()
            f3.sql('GRANT SELECT(' + column + ') ON ' + relation + ' TO pawbridge_f2_reader')
        check()

    owned = ()
    try:
        with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
            app = stub = None
            app_offset = 0
            try:
                app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                    jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                if database_snapshot() != baseline:
                    raise AssertionError('F18 startup changed existing business rows')
                storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(storage['counts'].values()) or storage['requests'] or storage['objects']:
                    raise AssertionError('F18 startup contacted S3')
                owned = prepare_business_rows()
                check(); check({'memberIds': str(REVERSE)}, values=())
                check({}, status=400, message=MISSING, layer='missing', kind='missing_parameter')
                check({'memberIds': ''}, status=400, message=INVALID_MEMBERS, kind='invalid_parameter')
                check({'memberIds': [str(FIRST) + ',' + str(SECOND), str(REVERSE)]},
                      status=400, message=INVALID_BINDING, kind='invalid_parameter')
                denied_select('pawbridge_user.contact_deletions', 'user_id', MEMBER_UNAVAILABLE, 'member_unavailable', invalid_first=True)
                denied_select('pawbridge_community.private_note_blocks', 'member_id', STORAGE_UNAVAILABLE, 'storage_unavailable')
                check({'memberIds': 'not-a-number'}, token=None, status=401, message='인증 토큰이 필요합니다.', layer='auth', kind='authentication')
                check(token='expired', status=401, message='유효하지 않거나 만료된 토큰입니다.', layer='auth', kind='authentication')
                check(status=403, message='접근할 수 없습니다.', layer='route', method='POST')
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F18 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F18 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline:
            raise AssertionError('F18 journey did not restore its successfully inserted owned rows')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F18 exceeded its one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F18 normal HTTP/single-pool shutdown evidence missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     'F18표시회원', 'F18 합성회원', 'f18-caller@fixture.invalid', *tokens.values())
        if any(value in text for value in forbidden):
            raise AssertionError('F18 application log exposed credentials, tokens or synthetic personal fields')
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F18 application connections remain after normal shutdown')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f18-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F18 outgoing sorted IDs, mixed error bodies, source-specific recovery and unchanged snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
