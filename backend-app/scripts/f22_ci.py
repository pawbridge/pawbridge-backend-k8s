#!/usr/bin/env python3
"""Private F22 draft; XML and packaged observations are not execution evidence yet."""
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
from uuid import UUID
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f19_ci as f19
import f20_ci as f20
import f21_ci as f21

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'cba94f898399674d4f708ce0c01e2192a18bc6c7'
TASK, SEOUL_TASK = 'privateNoteReadCompatibilityTest', 'privateNoteReadCompatibilitySeoulTest'
PG_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlPrivateNoteReadCompatibilityTest'
MVC_CLASS = 'com.pawbridge.backend.compatibility.F22LegacyPrivateNoteMvcTest'
# Original-only path6 and comparison MVC14 were measured locally; PG24/Seoul1/JAR9 remain hosted execution gates.
REPORTS = {TASK: {PG_CLASS: 24, MVC_CLASS: 14}, SEOUL_TASK: {PG_CLASS: 1}}
SEOUL_METHOD = 'givenDifferentMailboxNoteAndReadTstzMicroseconds__whenReadingAndSerializing__thenPreserveNoteAndReadInstantsWithoutZoneShift'
COUNTERS = ('detail_get_checks', 'successful_checks', 'rejected_checks', 'storage_unavailable_checks',
            'not_found_checks', 'authentication_checks', 'stream_denied_checks', 'denied_write_checks')
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (8, 4, 4, 1, 1, 1, 1, 1)))
TABLES = f21.TABLES
READABLE = {'private_notes': ('note_id', 'sender_id', 'recipient_id', 'body', 'context_type', 'context_id', 'created_at', 'expires_at'),
            'private_note_mailboxes': ('member_id', 'note_id', 'direction', 'read_at', 'favorite', 'created_at')}
ADDED = {'private_notes': ('recipient_id', 'body', 'context_type', 'context_id'), 'private_note_mailboxes': ('favorite',)}
CALLER, OTHER, NULL_MEMBER = 9976001, 9976002, 9976003
USERS = (CALLER, OTHER)
MEMBERS = (CALLER, OTHER, NULL_MEMBER)
NAMES = {CALLER: 'F22caller', OTHER: ' 原文 F22nickname '}
BODY = ' F22 synthetic private body\nraw line '
CREATED, READ_AT, MAILBOX_TIME = '2031-01-01T00:00:00.123456Z', '2031-01-01T00:00:01.654321Z', '2031-01-02T00:00:00.000001Z'
EXPIRES = '2099-01-01T00:00:00Z'
INBOX, SENT, NULL_SENDER, FOREIGN = (str(UUID(int=220000 + index)) for index in range(1, 5))
ALL_NOTES = (INBOX, SENT, NULL_SENDER, FOREIGN)
MAILBOXES = ((CALLER, INBOX), (CALLER, SENT), (CALLER, NULL_SENDER), (OTHER, FOREIGN))
OWNED = (USERS, MEMBERS, ALL_NOTES, MAILBOXES)
SUCCESS = '요청이 성공적으로 처리되었습니다.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'


def owned_sql(query='', *, input_text=None):
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F22 owned fixture SQL failed without exposing command, SQL or output') from None


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\nfixture=F21 notes/mailboxes and existing F20 blocks\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\nadded_reader_columns=notes4/mailboxes1\n'
            'reader_columns=notes8/mailboxes6\nnew_writer_acl=0\nnew_sequence_acl=0\n')


def require_owner():
    owner = f21.require_owner()  # Existing F19 identity/User-seven + F20 receipt, never F21 notes-four ACL.
    f21.require_f21_receipt(owner)
    f21.check_receipt(EVIDENCE / 'f22-fixture-reuse.txt', fixture_proof(owner))
    return owner


def catalog(query, stage):
    if stage not in ('shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F22 diagnostics are limited to static read-only catalog checks')
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
        primary = ''.join(char for char in primary if char.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f22-fixture-catalog-error.txt').write_text('stage=f22-private-note-' + stage
            + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F22 fixture query failed; see bounded f22-fixture-catalog-error.txt') from None


def require_fixture():
    owner = require_owner()
    if f20.require_fixture() != owner:
        raise SystemExit('F22 owner changed during inherited User/blocks validation')
    shapes = {
        'private_notes': "('note_id','uuid','NO',0,'',0),('sender_id','bigint','YES',0,'',0),('recipient_id','bigint','YES',0,'',0),"
                         "('body','text','NO',0,'',0),('reply_to','uuid','YES',0,'',0),('context_type','character varying','YES',0,'',10),"
                         "('context_id','bigint','YES',0,'',0),('created_at','timestamp with time zone','NO',6,'',0),('expires_at','timestamp with time zone','NO',6,'',0)",
        'private_note_mailboxes': "('member_id','bigint','NO',0,'',0),('note_id','uuid','NO',0,'',0),('direction','character varying','NO',0,'',5),"
                                 "('read_at','timestamp with time zone','YES',6,'',0),('favorite','boolean','NO',0,'false',0),('created_at','timestamp with time zone','NO',6,'',0)",
    }
    for schema, table in TABLES:
        relation, size = schema + '.' + table, 9 if table == 'private_notes' else 6
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') AND bool_and("
                   '(column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,\'\'),COALESCE(character_maximum_length,0)) IN ('
                   + shapes[table] + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                   "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F22 fixture differs from the exact original table shape: ' + table)
        allowed = 'column_name IN (' + ','.join(f4.literal(column) for column in READABLE[table]) + ')'
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + allowed + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F22 fixture lacks exact notes-eight/mailboxes-six/writer-zero/no-sequence privileges')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F22 fixture differs from the original validated PK/FK/CHECK/index definitions: ' + table)
    return owner


def database_snapshot():
    snapshot = f21.database_snapshot()
    if len(snapshot) != 16:
        raise SystemExit('F22 requires all sixteen prior business tables')
    return snapshot


def prepare():
    f2.require_hosted()
    owner = f21.require_fixture()  # Last old strict notes-four/mailboxes-five check, before extension only.
    target, proof = EVIDENCE / 'f22-fixture-reuse.txt', fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    before = database_snapshot()
    grants = ''.join('GRANT SELECT(' + ','.join(columns) + ') ON pawbridge_community.' + table
                     + ' TO pawbridge_f2_reader;\n' for table, columns in ADDED.items())
    # All ownership/old-shape/receipt checks finish before the only permission extension.
    owned_sql(input_text='BEGIN;\n' + grants + 'COMMIT;\n')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target.write_text(proof)
    require_fixture()
    if database_snapshot() != before:
        raise SystemExit('F22 permission-only extension changed business rows')
    print('Extended only five existing reader columns after the complete F21 stage; no DDL/writer/sequence changes')


@contextmanager
def denied_body():
    owner = require_fixture()
    owned_sql('REVOKE SELECT(body) ON pawbridge_community.private_notes FROM pawbridge_f2_reader')
    try:
        yield
    finally:
        # While a grant is absent, verify identity/receipts only; never old or current strict ACL.
        f19.require_identity(owner)
        f21.check_receipt(EVIDENCE / 'f20-fixture-reuse.txt', f21.f20_proof(owner))
        f21.require_f21_receipt(owner)
        f21.check_receipt(EVIDENCE / 'f22-fixture-reuse.txt', fixture_proof(owner))
        owned_sql('GRANT SELECT(body) ON pawbridge_community.private_notes TO pawbridge_f2_reader')
        require_fixture()


def prepare_business_rows():
    require_fixture()
    values = ','.join(map(str, MEMBERS))
    ids = ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES)
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id IN (' + ids + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id IN (' + values + '))') != '0':
        raise SystemExit('F22 synthetic User/member/note/mailbox keys must all be absent')
    users = ','.join('(' + str(identity) + ',' + f4.literal(NAMES[identity]) + ',' + f4.literal('f22-' + str(identity) + '@fixture.invalid')
                     + ",'F22 synthetic','GOOGLE','ROLE_USER')" for identity in USERS)
    definitions = ((INBOX, OTHER, CALLER, 'POST', '9223372036854775807'),
                   (SENT, CALLER, OTHER, 'REPORT', '8'), (NULL_SENDER, None, CALLER, None, None),
                   (FOREIGN, CALLER, OTHER, None, None))
    literal = lambda value: 'NULL' if value is None else str(value)
    notes = ','.join('(' + f4.literal(note) + '::uuid,' + literal(sender) + ',' + str(recipient) + ',' + f4.literal(BODY)
                     + ',' + ('NULL' if context is None else f4.literal(context)) + ',' + literal(context_id)
                     + ',' + f4.literal(CREATED) + '::timestamptz,' + f4.literal(EXPIRES) + '::timestamptz)'
                     for note, sender, recipient, context, context_id in definitions)
    mailboxes = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid,' + f4.literal('SENT' if note == SENT else 'INBOX')
                         + ',' + (f4.literal(READ_AT) + '::timestamptz' if note == SENT else 'NULL') + ','
                         + ('true' if note == INBOX else 'false') + ',' + f4.literal(MAILBOX_TIME) + '::timestamptz)'
                         for member, note in MAILBOXES)
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + users + '; '
              'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES' + ','.join('(' + str(i) + ')' for i in MEMBERS) + '; '
              'INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,context_type,context_id,created_at,expires_at) VALUES' + notes + '; '
              'INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + mailboxes + '; COMMIT;')
    return OWNED


def cleanup_business_rows(owned):
    f2.require_hosted()
    if owned != OWNED:
        raise ValueError('F22 cleanup requires only the successfully inserted exact keys')
    pairs = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid)' for member, note in MAILBOXES)
    queries = ('DELETE FROM pawbridge_community.private_note_mailboxes WHERE (member_id,note_id) IN (' + pairs + ')',
               'DELETE FROM pawbridge_community.private_notes WHERE body=' + f4.literal(BODY) + ' AND note_id IN ('
               + ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES) + ')',
               'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + ','.join(map(str, MEMBERS)) + ')',
               'DELETE FROM pawbridge_user.users WHERE (user_id,email) IN (' + ','.join('(' + str(i) + ','
               + f4.literal('f22-' + str(i) + '@fixture.invalid') + ')' for i in USERS) + ')')
    for query in queries:
        require_owner()
        owned_sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.is_symlink() or path.resolve() != expected:
        raise SystemExit('F22 token path is not owned by this run')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F22 token file must remain regular 0600')
        tokens = json.loads(path.read_text())
        parts = tokens['F22_CALLER'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == CALLER
                 and claims['role'] == 'ROLE_USER' and type(claims['exp']) in (int, float)
                 and math.isfinite(claims['exp']) and claims['exp'] >= time.time() + 60)
    except (OSError, AttributeError, KeyError, IndexError, ValueError, TypeError):
        raise SystemExit('F22 used token identity or freshness is invalid') from None
    if not valid:
        raise SystemExit('F22 used token identity, role or final freshness differs')
    return {'F22_CALLER': tokens['F22_CALLER']}


def require_measurements(kind):
    f2.require_hosted()
    if kind == 'xml':
        if (not SEOUL_METHOD or any(type(n) is not int or n <= 0 for classes in REPORTS.values() for n in classes.values())
                or REPORTS[SEOUL_TASK] != {PG_CLASS: 1}):
            raise SystemExit('F22 original binding/Java case counts and Seoul method are unmeasured')
    elif kind == 'jar':
        if (not isinstance(EXPECTED_JAR_COUNTS, dict) or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
                or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
            raise SystemExit('F22 packaged request counts are unmeasured')
    else:
        raise ValueError('Unknown F22 measurement gate')


def verify_results():
    require_measurements('xml')
    counts = {}
    for task, classes in REPORTS.items():
        directory = ROOT / 'boot/build/test-results' / task
        if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in classes}:
            raise SystemExit('F22 compatibility requires its exact UTC/Seoul XML class set')
        counts[task] = {}
        for name, expected_count in classes.items():
            try:
                suite = ET.parse(directory / ('TEST-' + name + '.xml')).getroot()
                values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
            except (OSError, ET.ParseError, KeyError, ValueError):
                raise SystemExit('F22 report is missing or malformed') from None
            cases = suite.findall('testcase')
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != name or values['tests'] != expected_count
                    or len(cases) != expected_count or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(case.attrib.get('classname') != name for case in cases)
                    or any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped'))
                    or (task == SEOUL_TASK and cases[0].attrib.get('name') not in (SEOUL_METHOD, SEOUL_METHOD + '()'))):
                raise SystemExit('F22 requires actual successful cases and only its named Seoul timestamp case')
            counts[task][name] = expected_count
    return counts


def expected_detail(note):
    return {'noteId': note, 'body': BODY, 'direction': 'SENT' if note == SENT else 'INBOX', 'createdAt': CREATED,
            'readAt': READ_AT if note == SENT else None, 'favorite': note == INBOX,
            'counterpartId': None if note == NULL_SENDER else OTHER,
            'counterpartNickname': '탈퇴한 회원' if note == NULL_SENDER else NAMES[OTHER],
            'canReply': note == INBOX, 'contextHref': '/community/9223372036854775807' if note == INBOX else '/reports/8' if note == SENT else None}


def smoke():
    require_measurements('jar')
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('F22 packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values(): print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'], F2_DB_USER=os.environ['F2_TEST_DB_USER'],
        F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'], F2_POOL_MAX_SIZE='1', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1')
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', timezone='Asia/Seoul', reader_pool_maximum=1, writer_pool_maximum=1,
                  database_changes=0, s3_requests=0)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f22-packaged-startup.log', EVIDENCE / 'f22-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(note=INBOX, status=200, token='F22_CALLER', message=SUCCESS, layer='business', kind=None, method='GET', suffix=''):
        headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
        before = snapshot()
        if token is not None: headers['Authorization'] = 'Bearer ' + load_tokens()[token]
        actual, raw, received = f3.request(base, '/api/v1/notes/' + note + suffix, method=method, headers=headers)
        if snapshot() != before: raise AssertionError('F22 request changed any business row or contacted S3')
        if (actual != status or received.get('Set-Cookie')
                or received.get('Cache-Control') != ('no-store' if layer == 'business' else f19.CACHE_CONTROL)):
            raise AssertionError('F22 request lost status/cache/cookie boundary')
        expected = ({'code': 200, 'message': SUCCESS, 'data': expected_detail(note)} if status == 200 else
                    {'message': message} if layer == 'business' else {'code': status, 'message': message, 'data': None})
        response = json.loads(raw)
        if response != expected: raise AssertionError('F22 lost its exact original detail/error contract')
        if status == 200 and (type(response['data']['favorite']) is not bool or type(response['data']['canReply']) is not bool
                or (response['data']['counterpartId'] is not None and type(response['data']['counterpartId']) is not int)):
            raise AssertionError('F22 numeric/boolean JSON values differ from original types')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') "
                     "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F22 request left an active DB transaction')
        if method == 'GET':
            result['detail_get_checks'] += 1
            result['successful_checks' if status == 200 else 'rejected_checks'] += 1
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
                if database_snapshot() != baseline: raise AssertionError('F22 startup changed business rows')
                state = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:
                    raise AssertionError('F22 startup contacted S3')
                owned = prepare_business_rows()
                check(); check(note=SENT); check(note=NULL_SENDER)
                check(note=FOREIGN, status=404, message='쪽지를 찾을 수 없습니다.', kind='not_found')
                with denied_body():
                    check(status=503, message=STORAGE_UNAVAILABLE, kind='storage_unavailable')
                check()
                check(status=401, token=None, message='인증 토큰이 필요합니다.', layer='auth', kind='authentication')
                check(note='stream', status=403, message='접근할 수 없습니다.', layer='route', kind='stream_denied')
                check(status=403, message='접근할 수 없습니다.', layer='route', kind='denied_write', method='PUT', suffix='/read')
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F22 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F22 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline: raise AssertionError('F22 owned seed cleanup did not restore all sixteen tables')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F22 exceeded the one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F22 normal HTTP/two-pool shutdown evidence is missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, 'F22 synthetic', *(name.strip() for name in NAMES.values() if name.strip()), *tokens.values(),
                     *('f22-' + str(i) + '@fixture.invalid' for i in USERS))
        if any(value in text for value in forbidden): raise AssertionError('F22 log exposed credentials, tokens or synthetic private data')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F22 application connections remain after shutdown')
        if {key: result[key] for key in COUNTERS} != EXPECTED_JAR_COUNTS:
            raise AssertionError('F22 packaged request counts differ from the reviewed contract')
        result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f22-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F22 private-detail journey and unchanged sixteen-table/S3 snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
