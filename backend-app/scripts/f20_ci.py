#!/usr/bin/env python3
"""Hosted-only block lists after F19; add only the existing block timestamp SELECT."""
from contextlib import contextmanager
import base64
import json
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import urllib.parse

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f17_ci as f17
import f19_ci as f19

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
CALLER, FIRST, NORMAL, PENDING, REVERSE = 9974001, 9974002, 9974003, 9974004, 9974014
TARGETS = tuple(range(FIRST, REVERSE))
IDS = (CALLER, *TARGETS, REVERSE)
USERS = (CALLER, FIRST, NORMAL, PENDING)
BLOCKS = tuple((CALLER, identity) for identity in TARGETS) + ((REVERSE, CALLER),)
OWNED = (USERS, IDS, BLOCKS, (PENDING,))
STORED_TIME = '2026-10-18T15:00:00.123456Z'
PATH = '/api/v1/notes/blocks'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
INVALID_PAGE = '페이지 범위를 확인해 주세요.'
WITHDRAWN = '탈퇴한 회원'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
TABLES = f17.TABLES
_columns = f17._columns
database_snapshot = f19.database_snapshot


def owned_sql(query):
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F20 owned fixture SQL failed without exposing its command or output') from None


def require_f19_evidence(owner):
    f2.require_hosted()
    target = EVIDENCE / 'f19-fixture-reuse.txt'
    expected = 'owner=' + owner + '\nfixture=F18/F17 unchanged\nnew_business_ddl=0\nnew_acl=0\nnew_marker=0\n'
    if (target.is_symlink() or target.resolve() != target or not target.is_file()
            or target.read_text() != expected):
        raise SystemExit('F20 requires unchanged F19 fixture reuse evidence owned by this run')


def require_owner():
    # This F16/User-seven chain never invokes the strict F17 blocks-two ACL.
    owner = f17.require_owner()
    require_f19_evidence(owner)
    return owner


def fixture_catalog(query, stage):
    if stage not in ('shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F20 diagnostics are restricted to static read-only catalog checks')
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError as failure:
        stderr = failure.stderr if isinstance(failure.stderr, str) else ''
        primary = next((line.strip() for line in stderr.splitlines() if re.match(r'^\s*(?:ERROR|FATAL):', line)),
                       '(no PostgreSQL primary error captured)')
        for key, value in os.environ.items():
            if value and any(label in key.upper() for label in ('PASSWORD', 'SECRET', 'TOKEN')):
                primary = primary.replace(value, '[redacted]')
        primary = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', primary)
        primary = ''.join(c for c in primary if c.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f20-fixture-catalog-error.txt').write_text('stage=f20-block-list-' + stage
            + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F20 fixture query failed; see bounded f20-fixture-catalog-error.txt') from None


def require_fixture():
    owner = require_owner()
    for schema, table in TABLES:
        relation = schema + '.' + table
        if table == 'contact_deletions':
            shape = "('user_id','bigint','NO',0,''),('started_at','timestamp with time zone','NO',6,'CURRENT_TIMESTAMP')"
            readable, size = ('user_id',), 2
        elif table == 'private_note_members':
            shape, readable, size = "('member_id','bigint','NO',0,'')", (), 1
        else:
            shape = "('member_id','bigint','NO',0,''),('blocked_id','bigint','NO',0,''),('created_at','timestamp with time zone','NO',6,'')"
            readable, size = ('member_id', 'blocked_id', 'created_at'), 3
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if fixture_catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') "
                "AND bool_and((column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,'')) IN ("
                + shape + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F20 fixture lacks the exact original shape/defaults: ' + table)
        predicate = 'false' if not readable else 'column_name IN (' + ','.join(f4.literal(c) for c in readable) + ')'
        if fixture_catalog('SELECT count(*)=' + str(size) + " AND bool_and("
                "has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=(" + predicate + ') '
                "AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES') "
                "AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') "
                "AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
                + where, 'acl') != 't':
            raise SystemExit('F20 fixture lacks exact pending-one/blocks-three/members-zero/writer-zero/no-sequence ACL: ' + table)
        pk = ('member_id', 'blocked_id') if table == 'private_note_blocks' else readable or ('member_id',)
        clauses = ["(SELECT count(*)=" + str({'contact_deletions': 2, 'private_note_members': 2, 'private_note_blocks': 4}[table])
                   + " FROM pg_constraint WHERE conrelid='" + relation + "'::regclass)",
                   "(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='p' "
                   "AND convalidated AND NOT condeferrable AND conkey=" + _columns(relation, pk) + ')']
        if table != 'contact_deletions':
            expression = 'member_id>0' if table == 'private_note_members' else 'member_id<>blocked_id'
            clauses.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation
                + "'::regclass AND contype='c' AND convalidated AND conkey=" + _columns(relation, pk)
                + " AND regexp_replace(pg_get_expr(conbin,conrelid),'::bigint|[()[:space:]]','','g')='" + expression + "')")
        for column in (() if table == 'private_note_members' else ('user_id',) if table == 'contact_deletions' else pk):
            target = 'pawbridge_user.users' if table == 'contact_deletions' else 'pawbridge_community.private_note_members'
            target_column = 'user_id' if table == 'contact_deletions' else 'member_id'
            clauses.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation
                + "'::regclass AND contype='f' AND convalidated AND NOT condeferrable AND NOT condeferred "
                "AND confrelid='" + target + "'::regclass AND confdeltype='c' AND confupdtype='a' AND confmatchtype='s' "
                'AND conkey=' + _columns(relation, (column,)) + ' AND confkey=' + _columns(target, (target_column,)) + ')')
        clauses.append("(SELECT count(*)=" + ('2' if table == 'private_note_blocks' else '1')
                       + " FROM pg_index WHERE indrelid='" + relation + "'::regclass)")
        if table == 'private_note_blocks':
            clauses.append("(SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am a ON a.oid=c.relam "
                "WHERE i.indrelid='" + relation + "'::regclass AND c.relname='idx_private_note_blocked_member' "
                "AND i.indisvalid AND i.indisready AND NOT i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL "
                "AND i.indnatts=1 AND a.amname='btree' AND i.indkey[0]=(SELECT attnum FROM pg_attribute WHERE attrelid='"
                + relation + "'::regclass AND attname='blocked_id'))")
        if fixture_catalog('SELECT ' + ' AND '.join(clauses), 'constraints') != 't':
            raise SystemExit('F20 fixture lacks exact validated PK/FK/CHECK/index definitions: ' + table)
    return owner


def prepare():
    f2.require_hosted()
    owner = f19.require_fixture()  # Last strict blocks-two check, before this single-column extension.
    require_f19_evidence(owner)
    proof = ('owner=' + owner + '\nfixture=F17/F18/F19 reused\nnew_business_ddl=0\n'
             'added_reader_column=private_note_blocks.created_at\nnew_writer_acl=0\nnew_sequence_acl=0\nnew_marker=0\n')
    target = EVIDENCE / 'f20-fixture-reuse.txt'
    if target.is_symlink() or target.resolve() != target or (target.exists() and target.read_text() != proof):
        raise SystemExit('Refusing differing or redirected F20 fixture evidence')
    before = database_snapshot()
    owned_sql('GRANT SELECT(created_at) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader')
    require_fixture()
    if database_snapshot() != before:
        raise SystemExit('F20 SELECT extension changed existing business rows')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target.write_text(proof)
    print('Added only the existing block timestamp SELECT after F19; old business rows unchanged')


@contextmanager
def denied_select(relation, column):
    if (relation, column) not in (('pawbridge_community.private_note_blocks', 'created_at'), ('pawbridge_user.users', 'nickname')):
        raise ValueError('F20 denial is limited to its timestamp or User nickname SELECT')
    owner = require_fixture()
    owned_sql('REVOKE SELECT(' + column + ') ON ' + relation + ' FROM pawbridge_f2_reader')
    try:
        yield
    finally:
        # User nickname revocation invalidates the F13/User-seven check in require_owner.
        # Only marker/target identity is safe before restoring that owned grant.
        if relation == 'pawbridge_user.users':
            f19.require_identity(owner)
            require_f19_evidence(owner)
        elif require_owner() != owner:
            raise SystemExit('F20 owner changed while timestamp SELECT was revoked')
        owned_sql('GRANT SELECT(' + column + ') ON ' + relation + ' TO pawbridge_f2_reader')
        require_fixture()


def prepare_business_rows():
    require_fixture()
    values = ','.join(str(identity) for identity in IDS)
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + values + '))') != '0':
        raise SystemExit('F20 synthetic member/User keys must all be absent before seeding')
    nicknames = {CALLER: 'F20caller', FIRST: '  ', NORMAL: 'F20normal', PENDING: 'F20pending'}
    users = ','.join('(' + str(identity) + ',' + f4.literal(nicknames[identity]) + ','
                     + f4.literal('f20-' + str(identity) + '@fixture.invalid')
                     + ",'F20 synthetic','GOOGLE','ROLE_USER')" for identity in USERS)
    members = ','.join('(' + str(identity) + ')' for identity in IDS)
    blocks = ','.join('(' + str(first) + ',' + str(second) + ',' + f4.literal(STORED_TIME) + '::timestamptz)'
                      for first, second in BLOCKS)
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + users + '; '
              'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES' + members + '; '
              'INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES' + blocks + '; '
              'INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(' + str(PENDING) + '); COMMIT;')
    return OWNED


def cleanup_business_rows(owned):
    f2.require_hosted()
    if owned != OWNED:
        raise ValueError('F20 cleanup requires exactly the successfully inserted User/member/block/pending keys')
    pairs = ','.join('(' + str(first) + ',' + str(second) + ')' for first, second in BLOCKS)
    for query in ('DELETE FROM pawbridge_community.private_note_blocks WHERE (member_id,blocked_id) IN (' + pairs + ')',
                  'DELETE FROM pawbridge_user.contact_deletions WHERE user_id=' + str(PENDING),
                  'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + ','.join(map(str, IDS)) + ')',
                  'DELETE FROM pawbridge_user.users WHERE (user_id,email) IN (' + ','.join('(' + str(identity) + ','
                      + f4.literal('f20-' + str(identity) + '@fixture.invalid') + ')' for identity in USERS) + ')'):
        require_owner()
        owned_sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.resolve() != expected or path.is_symlink():
        raise SystemExit('F20 token file must belong to this hosted run without a symlink')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F20 synthetic token file must remain a regular 0600 file')
        tokens = json.loads(path.read_text())
        parts = tokens['F20_CALLER'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == CALLER
                 and claims['role'] == 'ROLE_USER' and type(claims['exp']) in (int, float)
                 and math.isfinite(claims['exp']) and claims['exp'] >= time.time() + 60)
    except (OSError, AttributeError, KeyError, IndexError, ValueError, TypeError):
        raise SystemExit('F20 used token is missing, malformed or has invalid identity/freshness') from None
    if not valid:
        raise SystemExit('F20 used token identity, role or final freshness differs from its contract')
    return {'F20_CALLER': tokens['F20_CALLER']}


def expected_page(page):
    targets = sorted(TARGETS, reverse=True)[page * 10:(page + 1) * 10]
    content = [{'memberId': identity, 'nickname': '  ' if identity == FIRST else 'F20normal' if identity == NORMAL else WITHDRAWN,
                'createdAt': STORED_TIME} for identity in targets]
    return {'content': content, 'totalElements': 12, 'totalPages': 2, 'number': page}


def smoke():
    owner = require_fixture()
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
              'block_list_get_checks': 0, 'successful_checks': 0, 'rejected_checks': 0, 'page_checks': 0,
              'empty_page_checks': 0, 'invalid_page_checks': 0, 'member_unavailable_checks': 0,
              'storage_unavailable_checks': 0, 'authentication_checks': 0, 'denied_route_checks': 0,
              'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f20-packaged-startup.log', EVIDENCE / 'f20-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(page=0, status=200, token='F20_CALLER', message=SUCCESS, layer='business', kind=None, method='GET'):
        headers = {'X-User-Id': str(REVERSE), 'X-User-Role': 'ROLE_ADMIN'}
        path = PATH + ('?page=' + urllib.parse.quote(str(page)) if method == 'GET' else '')
        before = snapshot()
        if token is not None:
            headers['Authorization'] = 'Bearer ' + load_tokens()[token]
        actual, raw, received = f3.request(base, path, method=method, headers=headers)
        if snapshot() != before:
            raise AssertionError('F20 request changed business rows or contacted S3')
        cache = 'no-store' if layer == 'business' else f19.CACHE_CONTROL
        if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != cache:
            raise AssertionError('F20 request lost status, cache or cookie boundary')
        expected = ({'code': 200, 'message': SUCCESS, 'data': expected_page(page)} if status == 200 else
                    {'message': message} if layer == 'business' else {'code': status, 'message': message, 'data': None})
        response = json.loads(raw)
        if response != expected:
            raise AssertionError('F20 lost its exact original page, display, order, timestamp or error contract')
        if status == 200 and (any(type(response['data'][field]) is not int for field in ('totalElements', 'totalPages', 'number'))
                              or any(type(item['memberId']) is not int for item in response['data']['content'])):
            raise AssertionError('F20 page and member identifiers must remain integral JSON numbers')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                     "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F20 request left an active DB transaction')
        if method == 'GET':
            result['block_list_get_checks'] += 1
            result['successful_checks' if status == 200 else 'rejected_checks'] += 1
            if status == 200:
                result['page_checks'] += 1
                if not response['data']['content']: result['empty_page_checks'] += 1
        if kind: result[kind + '_checks'] += 1

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
                    raise AssertionError('F20 startup changed existing business rows')
                storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(storage['counts'].values()) or storage['requests'] or storage['objects']:
                    raise AssertionError('F20 startup contacted S3')
                owned = prepare_business_rows()
                check(); check(page=1); check(page=2)
                with denied_select('pawbridge_community.private_note_blocks', 'created_at'):
                    check(status=503, message=STORAGE_UNAVAILABLE, kind='storage_unavailable')
                check()
                with denied_select('pawbridge_user.users', 'nickname'):
                    check(page=-1, status=400, message=INVALID_PAGE, kind='invalid_page')
                    check(status=503, message=MEMBER_UNAVAILABLE, kind='member_unavailable')
                check(page=1)
                check(status=401, token=None, message='인증 토큰이 필요합니다.', layer='auth', kind='authentication')
                check(status=403, message='접근할 수 없습니다.', layer='route', kind='denied_route', method='POST')
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F20 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F20 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline:
            raise AssertionError('F20 journey did not restore exactly its successfully inserted owned rows')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F20 exceeded its one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F20 normal HTTP/single-pool shutdown evidence missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     'F20caller', 'F20normal', 'F20pending', 'F20 synthetic', *tokens.values(),
                     *('f20-' + str(identity) + '@fixture.invalid' for identity in USERS))
        if any(value in text for value in forbidden):
            raise AssertionError('F20 application log exposed credentials, tokens or synthetic personal fields')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F20 application connections remain after normal shutdown')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f20-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F20 pages, original display/time, source-specific recovery and unchanged snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
