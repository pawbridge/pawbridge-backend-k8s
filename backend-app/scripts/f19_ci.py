#!/usr/bin/env python3
"""Hosted-only administrator count; reuse the unchanged F18/F17 fixture and ACL."""
from contextlib import contextmanager
import base64
import json
import math
import os
from pathlib import Path
import stat
import subprocess
import sys
import time

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f18_ci as f18

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
ADMIN, USER = 9973001, 9973002
OWNED = (USER,)
EMAIL = 'f19-role@fixture.invalid'
PATH = '/api/v1/admin/users/stats/total-users'
ADJACENT = '/api/v1/admin/users/stats/daily-signups/extra'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
ADMIN_ONLY = '관리자 권한이 필요합니다.'
SERVER_ERROR = '서버 에러!'
# Observed in the actual Boot Security HTTP test for success, auth/authorization and server failure.
# Standalone original count MVC has no explicit Cache-Control policy.
CACHE_CONTROL = 'no-cache, no-store, max-age=0, must-revalidate'
require_fixture = f18.require_fixture
require_owner = f18.require_owner
database_snapshot = f18.database_snapshot


def owned_sql(query):
    """Keep command arguments, SQL, output and exception details out of diagnostics."""
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F19 owned fixture SQL failed without exposing its command or output') from None


def require_identity(expected_owner=None):
    """No ACL checks here: the users.user_id grant may be temporarily revoked."""
    owner = f2.require_hosted()
    stages = ((3, f3.MARKER), (16, f18.f17.f16.MARKER), (17, f18.f17.MARKER))
    if ((expected_owner is not None and expected_owner != owner)
            or any(os.environ.get('F' + str(stage) + '_CI_OWNER') != owner for stage, _ in stages)
            or os.environ.get('F3_TEST_WRITER_USER') != f3.WRITER):
        raise SystemExit('F19 fixture owner identity mismatch')
    # f3.sql independently verifies hosted/container label/loopback/DB/admin/F2 marker.
    query = "SELECT stage || ':' || value || ':' || owner FROM (" + ' UNION ALL '.join(
        'SELECT ' + str(stage) + ' AS stage,value,owner FROM pawbridge_f' + str(stage) + '_fixture.marker'
        for stage, _ in stages) + ') markers ORDER BY stage'
    if owned_sql(query) != '\n'.join(str(stage) + ':' + marker + ':' + owner for stage, marker in stages):
        raise SystemExit('F19 fixture marker identity mismatch')
    return owner


@contextmanager
def denied_user_id_select():
    owner = require_fixture()
    owned_sql('REVOKE SELECT(user_id) ON pawbridge_user.users FROM pawbridge_f2_reader')
    try:
        yield
    finally:
        require_identity(owner)
        owned_sql('GRANT SELECT(user_id) ON pawbridge_user.users TO pawbridge_f2_reader')
        require_fixture()


def prepare():
    f2.require_hosted()
    owner = require_fixture()
    before = database_snapshot()
    proof = ('owner=' + owner + '\nfixture=F18/F17 unchanged\nnew_business_ddl=0\nnew_acl=0\nnew_marker=0\n')
    target = EVIDENCE / 'f19-fixture-reuse.txt'
    if target.is_symlink() or target.resolve() != target or (target.exists() and target.read_text() != proof):
        raise SystemExit('Refusing differing or redirected F19 fixture reuse evidence')
    if database_snapshot() != before:
        raise SystemExit('F19 fixture reuse changed existing business rows')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target.write_text(proof)
    print('Reused the unchanged F18/F17 fixture, owner and exact ACL')


def prepare_business_rows():
    require_fixture()
    if owned_sql('SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + str(ADMIN) + ',' + str(USER) + ')') != '0':
        raise SystemExit('F19 principals must not have pre-existing User rows')
    inserted = owned_sql('WITH inserted AS (INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES('
                         + str(USER) + ",'F19role9973002','" + EMAIL + "','F19 synthetic','GOOGLE','ROLE_ADMIN') RETURNING user_id) "
                         'SELECT user_id FROM inserted')
    if inserted != str(USER):
        raise SystemExit('F19 seed did not return the single owned User key')
    return OWNED


def cleanup_business_rows(owned):
    f2.require_hosted()
    if owned != OWNED:
        raise ValueError('F19 cleanup requires the exact successfully inserted User key')
    require_identity()
    owned_sql('DELETE FROM pawbridge_user.users WHERE user_id=' + str(USER) + " AND email='" + EMAIL + "'")


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.resolve() != expected or path.is_symlink():
        raise SystemExit('F19 token file must belong to this hosted run without a symlink')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F19 synthetic token file must remain a regular 0600 file')
        tokens = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('F19 synthetic token file is missing or malformed') from None
    if not isinstance(tokens, dict) or not {'F19_ADMIN', 'F19_USER'}.issubset(tokens):
        raise SystemExit('F19 requires both refreshed real-issuer principal keys')
    now = time.time()
    for key, identity, role in (('F19_ADMIN', ADMIN, 'ROLE_ADMIN'), ('F19_USER', USER, 'ROLE_USER')):
        try:
            parts = tokens[key].split('.')
            claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
            valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == identity
                     and claims['role'] == role and type(claims['exp']) in (int, float)
                     and math.isfinite(claims['exp']) and claims['exp'] >= now + 60)
        except (AttributeError, KeyError, IndexError, ValueError, TypeError):
            valid = False
        if not valid:
            raise SystemExit('F19 used token identity, role or final freshness differs from its contract')
    return {key: tokens[key] for key in ('F19_ADMIN', 'F19_USER')}


def smoke():
    owner = require_fixture()
    if not isinstance(CACHE_CONTROL, str) or not CACHE_CONTROL:
        raise SystemExit('F19 cache header contract awaits the actual Boot Security observation')
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
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
              'total_user_count_get_checks': 0, 'successful_checks': 0, 'rejected_checks': 0,
              'administrator_denied_checks': 0, 'sql_failure_checks': 0, 'authentication_checks': 0,
              'denied_route_checks': 0, 'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f19-packaged-startup.log', EVIDENCE / 'f19-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''
    expected_count = None

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(status=200, token='F19_ADMIN', message=SUCCESS, path=PATH, kind=None):
        headers = {'X-User-Id': str(USER), 'X-User-Role': 'ROLE_ADMIN'}
        before = snapshot()
        if token is not None:
            headers['Authorization'] = 'Bearer ' + load_tokens()[token]
        actual, raw, received = f3.request(base, path, headers=headers)
        if snapshot() != before:
            raise AssertionError('F19 request changed business rows or contacted S3')
        if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != CACHE_CONTROL:
            raise AssertionError('F19 request lost its status, observed Security cache header or cookie boundary')
        response = json.loads(raw)
        expected = {'code': status, 'message': message, 'data': expected_count if status == 200 else None}
        if response != expected or (status == 200 and type(response.get('data')) is not int):
            raise AssertionError('F19 lost its exact integral count or safe common envelope')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                     "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F19 request left an active DB transaction')
        if path == PATH:
            result['total_user_count_get_checks'] += 1
            result['successful_checks' if status == 200 else 'rejected_checks'] += 1
        if kind:
            result[kind + '_checks'] += 1

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
                    raise AssertionError('F19 startup changed existing business rows')
                storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(storage['counts'].values()) or storage['requests'] or storage['objects']:
                    raise AssertionError('F19 startup contacted S3')
                owned = prepare_business_rows()
                expected_count = len(database_snapshot()['users'])
                check()
                with denied_user_id_select():
                    check(status=403, token='F19_USER', message=ADMIN_ONLY, kind='administrator_denied')
                    check(status=500, message=SERVER_ERROR, kind='sql_failure')
                check()
                check(status=401, token=None, message='인증 토큰이 필요합니다.', kind='authentication')
                check(status=403, message='접근할 수 없습니다.', path=ADJACENT, kind='denied_route')
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F19 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F19 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline:
            raise AssertionError('F19 journey did not restore its successfully inserted owned User')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F19 exceeded its one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F19 normal HTTP/single-pool shutdown evidence missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     'F19role9973002', 'F19 synthetic', EMAIL, *tokens.values())
        if any(value in text for value in forbidden):
            raise AssertionError('F19 application log exposed credentials, tokens or synthetic personal fields')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F19 application connections remain after normal shutdown')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f19-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F19 exact JWT role, safe SQL failure/recovery and unchanged snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
