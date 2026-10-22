#!/usr/bin/env python3
"""Hosted-only note recipient reads after F3-F16; no messages, writes or lifecycle jobs."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f16_ci as f16

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
MARKER = 'pawbridge-f17-ci-only-v1'
SOURCE = '17a8bc72caf9f3a46b52e133e0a46c492ae52614'
DDL_HASHES = {
    'user-service/src/migration/resources/db/postgresql/V3__private_note_deletion_coordination.sql': '6ff3b954f5c16406a787229ee03b77e8e3fc4e562969782c1f32b9a2dae9bce1',
    'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql': '3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119',
    'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql': 'd2572afc2644df3f932872bcd757e47937cdf7d5b89789439cb103de6c71512d',
}
TASK = 'noteRecipientCompatibilityTest'
CLASSES = ('com.pawbridge.backend.compatibility.PostgresqlNoteRecipientCompatibilityTest',
           'com.pawbridge.backend.compatibility.F17LegacyRecipientLookupTest')
CALLER, OTHER = 9971001, 9971002
IDS = (CALLER, OTHER)
NICKNAMES = {CALLER: 'F17보낸이', OTHER: 'F17받는이'}
PATH = '/api/v1/notes/recipients/'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
BLOCKED = '이 상대에게 쪽지를 보낼 수 없습니다.'
INACTIVE = '이용할 수 없는 회원입니다.'
LOGIN = '로그인이 필요합니다.'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
INVALID = '요청 형식과 로그인 정보를 확인해 주세요.'
TABLES = (('pawbridge_user', 'contact_deletions'), ('pawbridge_community', 'private_note_members'),
          ('pawbridge_community', 'private_note_blocks'))


def verify_results():
    f2.require_hosted()
    directory = ROOT / 'boot/build/test-results' / TASK
    if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in CLASSES}:
        raise SystemExit('F17 compatibility requires its exact two-class XML set')
    counts = {}
    for name in CLASSES:
        try:
            suite = ET.parse(directory / ('TEST-' + name + '.xml')).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F17 compatibility report missing or malformed') from None
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != name or values['tests'] <= 0
                or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or len(cases) != values['tests'] or any(c.attrib.get('classname') != name for c in cases)
                or any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped'))):
            raise SystemExit('F17 compatibility requires executed successful unskipped cases')
        counts[name] = values['tests']
    print('Verified F17 single UTC PG and lightweight original lookup reports: ' + json.dumps(counts, sort_keys=True))
    return counts


def verified_ddl():
    f2.require_hosted()
    inputs = {}
    for path, digest in DDL_HASHES.items():
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent)
        if hashlib.sha256(raw).hexdigest() != digest:
            raise SystemExit('F17 original migration checksum mismatch: ' + path)
        inputs[Path(path).name] = raw.decode('utf-8')
    pending = inputs['V3__private_note_deletion_coordination.sql'].split('CREATE TABLE contact_deletions (', 1)[1]
    pending = 'CREATE TABLE contact_deletions (' + pending
    notes = inputs['V6__private_notes.sql']
    members = 'CREATE TABLE private_note_members (' + notes.split('CREATE TABLE private_note_members (', 1)[1].split('CREATE TABLE private_notes (', 1)[0]
    blocks = 'CREATE TABLE private_note_blocks (' + notes.split('CREATE TABLE private_note_blocks (', 1)[1]
    index = next(line for line in inputs['V8__private_note_reference_indexes.sql'].splitlines()
                 if line.startswith('CREATE INDEX idx_private_note_blocked_member '))
    fragments = (pending, members, blocks, index)
    ddl = (ROOT / 'scripts/f17_fixture.sql').read_text()
    for fragment in fragments:
        expected = fragment.strip().replace('CREATE TABLE contact_deletions', 'CREATE TABLE pawbridge_user.contact_deletions')
        expected = expected.replace('REFERENCES users(', 'REFERENCES pawbridge_user.users(')
        for table in ('private_note_members', 'private_note_blocks'):
            expected = expected.replace('CREATE TABLE ' + table, 'CREATE TABLE pawbridge_community.' + table)
            expected = expected.replace('REFERENCES ' + table + '(', 'REFERENCES pawbridge_community.' + table + '(')
            expected = expected.replace('ON ' + table + ' ', 'ON pawbridge_community.' + table + ' ')
        if expected not in ddl:
            raise SystemExit('F17 fixture differs from pinned V3/V6/V8 three-table/index fragments')
    evidence = 'source_sha=' + SOURCE + '\n' + ''.join(digest + '  ' + path + '\n' for path, digest in DDL_HASHES.items())
    return ddl, evidence


def fixture_catalog(query, stage):
    """Only static read-only F17 catalog checks receive masked bounded diagnostics."""
    if stage not in ('marker', 'shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F17 diagnostics are restricted to static read-only catalog checks')
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
        (EVIDENCE / 'f17-fixture-catalog-error.txt').write_text('stage=f17-recipient-' + stage
            + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F17 fixture query failed; see bounded f17-fixture-catalog-error.txt') from None


def require_owner():
    # The F16 chain remains valid while an F17-only SELECT is temporarily revoked.
    owner = f16.require_fixture()
    if os.environ.get('F17_CI_OWNER') != owner:
        raise SystemExit('F17 fixture owner mismatch')
    if fixture_catalog("SELECT value || ':' || owner FROM pawbridge_f17_fixture.marker", 'marker') != MARKER + ':' + owner:
        raise SystemExit('F17 fixture identity mismatch')
    return owner


def _columns(relation, names):
    return 'ARRAY[' + ','.join("(SELECT attnum FROM pg_attribute WHERE attrelid='" + relation
        + "'::regclass AND attname='" + name + "')" for name in names) + ']'


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
            readable, size = ('member_id', 'blocked_id'), 3
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if fixture_catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') "
                "AND bool_and((column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,'')) IN ("
                + shape + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F17 fixture lacks the exact original shape/defaults: ' + table)
        predicate = 'false' if not readable else 'column_name IN (' + ','.join(f4.literal(c) for c in readable) + ')'
        if fixture_catalog('SELECT count(*)=' + str(size) + " AND bool_and("
                "has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=(" + predicate + ') '
                "AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES') "
                "AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') "
                "AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
                + where, 'acl') != 't':
            raise SystemExit('F17 fixture lacks exact pending-one/blocks-two/members-zero/writer-zero/no-sequence ACL: ' + table)
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
            raise SystemExit('F17 fixture lacks exact validated PK/FK/CHECK/index definitions: ' + table)
    return owner


def database_snapshot():
    previous = f16.database_snapshot()
    for schema, table in TABLES:
        order = 'user_id' if table == 'contact_deletions' else 'member_id,blocked_id' if table == 'private_note_blocks' else 'member_id'
        previous[table] = json.loads(f3.sql("SELECT COALESCE(json_agg(r ORDER BY " + order + "),'[]'::json) FROM " + schema + '.' + table + ' r'))
    return previous


def prepare():
    f2.require_hosted()
    owner = f16.require_fixture()
    ddl, evidence = verified_ddl()
    f2.export_environment({'F17_CI_OWNER': owner})
    before = f16.database_snapshot()
    f3.sql(input_text=ddl)
    if f16.database_snapshot() != before:
        raise SystemExit('F17 fixture extension changed existing business rows')
    require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f17-ddl-sha256.txt').write_text(evidence)
    print('Added only pinned contact-deletion/members/blocks fragments and reader three-column SELECT after F16')


def prepare_business_rows():
    require_fixture()
    values = ','.join('(' + str(identity) + ',' + f4.literal(NICKNAMES[identity]) + ','
        + f4.literal('f17-' + str(identity) + '@fixture.invalid') + ",'F17 합성회원','GOOGLE','ROLE_USER')" for identity in IDS)
    # Both INSERTs commit together. A collision is an error; no pre-existing row is replaced or claimed.
    f3.sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES ' + values
           + '; INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (' + str(CALLER) + '),('
           + str(OTHER) + '); COMMIT;')
    return IDS


def cleanup_business_rows(owned):
    if tuple(owned) != IDS:
        raise ValueError('F17 cleanup requires the exact successfully inserted principal keys')
    keys = ','.join(str(identity) for identity in owned)
    for query in ('DELETE FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + keys + ') AND blocked_id IN (' + keys + ')',
                  'DELETE FROM pawbridge_user.contact_deletions WHERE user_id IN (' + keys + ')',
                  'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + keys + ')',
                  'DELETE FROM pawbridge_user.users WHERE ' + ' OR '.join('(user_id=' + str(identity)
                    + ' AND email=' + f4.literal('f17-' + str(identity) + '@fixture.invalid') + ')' for identity in owned)):
        require_owner()
        f3.sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.resolve() != expected or path.is_symlink():
        raise SystemExit('F17 token file must belong to this hosted run without a symlink')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F17 synthetic token file must remain a regular 0600 file')
        tokens = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('F17 synthetic token file is missing or malformed') from None
    if not isinstance(tokens, dict) or not {'F17_CALLER', 'F17_OTHER', 'expired'}.issubset(tokens):
        raise SystemExit('F17 requires the refreshed real-issuer principal and expired token map')
    now = time.time()
    for key, identity in (('F17_CALLER', CALLER), ('F17_OTHER', OTHER), ('expired', CALLER)):
        try:
            raw = tokens[key].split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
            valid = (type(claims['userId']) is int and claims['userId'] == identity and claims['role'] == 'ROLE_USER'
                     and type(claims['exp']) in (int, float)
                     and (claims['exp'] < now if key == 'expired' else claims['exp'] > now + 60))
        except (AttributeError, KeyError, IndexError, ValueError, TypeError):
            valid = False
        if not valid:
            raise SystemExit('F17 used token identity or final freshness differs from the fixture contract')
    return {key: tokens[key] for key in ('F17_CALLER', 'F17_OTHER', 'expired')}


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file(): raise SystemExit('Packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values(): print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'], F2_DB_USER=f3.APP,
        F2_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], F2_POOL_MAX_SIZE='4', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'pool_maximum': 4,
              'recipient_get_checks': 0, 'successful_checks': 0, 'rejected_checks': 0, 'denied_route_checks': 0,
              'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f17-packaged-startup.log', EVIDENCE / 'f17-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(member=OTHER, token='F17_CALLER', status=200, message=SUCCESS, layer='business', method='GET', path=None):
        headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN', 'Content-Type': 'application/json'}
        if token is not None: headers['Authorization'] = 'Bearer ' + tokens.get(token, token)
        before = snapshot()
        actual, raw, received = f3.request(base, path or PATH + str(member) + '?userId=' + str(OTHER), method=method, headers=headers)
        if snapshot() != before:
            raise AssertionError('Recipient GET changed a business row or contacted S3')
        if actual != status or received.get('Set-Cookie'):
            raise AssertionError('Recipient request lost its expected status or created a cookie')
        response = json.loads(raw)
        if layer == 'business' and received.get('Cache-Control') != 'no-store':
            raise AssertionError('Recipient success/service/binding errors must carry no-store')
        expected = {'code': status, 'message': message, 'data': None} if layer != 'business' or status == 200 else {'message': message}
        if status == 200:
            expected['data'] = {'userId': member, 'nickname': NICKNAMES[member], 'active': True, 'deletionPending': False}
        if (response != expected or (status == 200 and (type(response['data']['userId']) is not int
                or type(response['data']['active']) is not bool or type(response['data']['deletionPending']) is not bool))):
            raise AssertionError('Recipient response lost its exact four fields or scoped safe error body')
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F17 request left an active DB transaction')
        result['denied_route_checks' if layer == 'route' else 'recipient_get_checks'] += 1
        if layer != 'route': result['successful_checks' if status == 200 else 'rejected_checks'] += 1

    def mutate(query):
        require_fixture()
        f3.sql(query)

    def denied_select(relation, column, member, message):
        mutate('REVOKE SELECT(' + column + ') ON ' + relation + ' FROM pawbridge_f2_reader')
        try: check(member, status=503, message=message)
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
                    raise AssertionError('F17 startup changed existing business rows')
                storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(storage['counts'].values()) or storage['requests'] or storage['objects']:
                    raise AssertionError('F17 startup contacted S3')
                owned = prepare_business_rows()
                check(); check(CALLER, token='F17_OTHER'); check(CALLER, status=403, message=BLOCKED)
                for member, blocked in ((CALLER, OTHER), (OTHER, CALLER)):
                    predicate = 'member_id=' + str(member) + ' AND blocked_id=' + str(blocked)
                    mutate('INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES ('
                           + str(member) + ',' + str(blocked) + ",TIMESTAMPTZ '2026-10-19 00:00:00+00')")
                    check(status=403, message=BLOCKED)
                    mutate('DELETE FROM pawbridge_community.private_note_blocks WHERE ' + predicate)
                mutate('INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (' + str(OTHER) + ')')
                check(status=403, message=INACTIVE)
                mutate('DELETE FROM pawbridge_user.contact_deletions WHERE user_id=' + str(OTHER))
                check(0, status=401, message=LOGIN); check(-1, status=401, message=LOGIN)
                # The query source determines the error, before self/late-ID policy can run.
                denied_select('pawbridge_user.contact_deletions', 'user_id', CALLER, MEMBER_UNAVAILABLE)
                denied_select('pawbridge_community.private_note_blocks', 'member_id', 0, STORAGE_UNAVAILABLE)
                check('not-a-number', token=None, status=401, message='인증 토큰이 필요합니다.', layer='auth')
                check(token='expired', status=401, message='유효하지 않거나 만료된 토큰입니다.', layer='auth')
                check(token='definitely-not-a-jwt', status=401, message='유효하지 않거나 만료된 토큰입니다.', layer='auth')
                check('not-a-number', status=400, message=INVALID)
                check(status=403, message='접근할 수 없습니다.', layer='route', path=PATH + str(OTHER) + '/extra')
                check(status=403, message='접근할 수 없습니다.', layer='route', method='POST', path=PATH + str(OTHER))
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F17 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F17 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline:
            raise AssertionError('F17 packaged journey did not restore its successfully inserted owned rows')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F17 exceeded its one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F17 normal HTTP/two-pool shutdown evidence missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     *NICKNAMES.values(), 'F17 합성회원', *(f'f17-{identity}@fixture.invalid' for identity in IDS), *tokens.values())
        if any(value in text for value in forbidden):
            raise AssertionError('F17 application log exposed credentials, tokens or synthetic personal fields')
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F17 application connections remain after normal shutdown')
        result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f17-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F17 recipient policy, source-specific SQL recovery, no-side-effects and two-pool shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
