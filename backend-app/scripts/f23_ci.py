#!/usr/bin/env python3
"""F23 owned mailbox-page CI; refuse local resources and preserve inherited read-only fixture contracts."""
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
import f22_ci as f22

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
TASK, SEOUL_TASK = 'privateNoteListCompatibilityTest', 'privateNoteListCompatibilitySeoulTest'
PG_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlPrivateNoteListCompatibilityTest'
MVC_CLASS = 'com.pawbridge.backend.compatibility.F23LegacyPrivateNoteListMvcTest'
# Original-only query6 and actual MVC12 passed locally; hosted PG16/Seoul1/JAR12 remain separate gates.
REPORTS = {TASK: {PG_CLASS: 16, MVC_CLASS: 12}, SEOUL_TASK: {PG_CLASS: 1}}
SEOUL_METHOD = 'givenMailboxUuidSortDiffersFromNoteAndJavaSignedOrder__whenListingAndSerializing__thenPreservePgOrderAndNoteReadInstantsWithoutZoneShift'
COUNTERS = ('list_get_checks', 'successful_checks', 'rejected_checks', 'storage_unavailable_checks',
            'invalid_page_checks', 'authentication_checks', 'stream_denied_checks', 'detail_get_checks', 'denied_write_checks')
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (9, 6, 3, 1, 1, 1, 1, 1, 1)))
TABLES = f22.TABLES
READABLE = f22.READABLE
CALLER, OTHER, NULL_MEMBER = 9977001, 9977002, 9977003
USERS = (CALLER, OTHER)
MEMBERS = (CALLER, OTHER, NULL_MEMBER)
NAMES = {CALLER: 'F23caller', OTHER: ' 原文 F23nickname '}
BODY = ' F23 synthetic private body\nraw line '
CREATED, READ_AT, MAILBOX_TIME = '2031-01-01T00:00:00.123456Z', '2031-01-01T00:00:01.654321Z', '2031-01-02T00:00:00.000001Z'
EXPIRES = '2099-01-01T00:00:00Z'
INBOX = tuple(str(UUID(int=230000 + index)) for index in range(1, 13))
SENT, FOREIGN, EXPIRED = (str(UUID(int=230000 + index)) for index in range(13, 16))
ALL_NOTES = (*INBOX, SENT, FOREIGN, EXPIRED)
MAILBOXES = tuple((OTHER if note == FOREIGN else CALLER, note) for note in ALL_NOTES)
OWNED = (USERS, MEMBERS, ALL_NOTES, MAILBOXES)
SUCCESS = '요청이 성공적으로 처리되었습니다.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'


def owned_sql(query='', *, input_text=None):
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F23 owned fixture SQL failed without exposing command, SQL or output') from None


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\nfixture=unchanged F22 notes-eight/mailboxes-six and F20 blocks\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\nadded_reader_columns=0\n'
            'reader_columns=notes8/mailboxes6\nnew_writer_acl=0\nnew_sequence_acl=0\n')


def require_owner():
    return f22.require_owner()


def require_fixture():
    return f22.require_fixture()


def database_snapshot():
    snapshot = f21.database_snapshot()
    if len(snapshot) != 16:
        raise SystemExit('F23 requires all sixteen prior business tables')
    return snapshot


def prepare():
    f2.require_hosted()
    owner = require_fixture()
    target, proof = EVIDENCE / 'f23-fixture-reuse.txt', fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    before = database_snapshot()
    # No SQL mutation, grant, old strict checker or new marker for this existing GET.
    if require_fixture() != owner or database_snapshot() != before:
        raise SystemExit('F23 unchanged fixture identity or rows changed during validation')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target.write_text(proof)
    print('Reused the unchanged F22 fixture; no DDL, grants, writer, sequence or business changes')


@contextmanager
def denied_mailbox_time():
    owner = require_fixture()
    owned_sql('REVOKE SELECT(created_at) ON pawbridge_community.private_note_mailboxes FROM pawbridge_f2_reader')
    try:
        yield
    finally:
        # Only identity/receipts while the grant is absent; a strict checker cannot repair its own denial.
        f19.require_identity(owner)
        f21.check_receipt(EVIDENCE / 'f20-fixture-reuse.txt', f21.f20_proof(owner))
        f21.require_f21_receipt(owner)
        f21.check_receipt(EVIDENCE / 'f22-fixture-reuse.txt', f22.fixture_proof(owner))
        owned_sql('GRANT SELECT(created_at) ON pawbridge_community.private_note_mailboxes TO pawbridge_f2_reader')
        require_fixture()


def prepare_business_rows():
    require_fixture()
    values = ','.join(map(str, MEMBERS))
    ids = ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES)
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id IN (' + ids + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id IN (' + values + '))') != '0':
        raise SystemExit('F23 synthetic User/member/note/mailbox keys must all be absent')
    users = ','.join('(' + str(identity) + ',' + f4.literal(NAMES[identity]) + ',' + f4.literal('f23-' + str(identity) + '@fixture.invalid')
                     + ",'F23 synthetic','GOOGLE','ROLE_USER')" for identity in USERS)
    definitions = tuple((note, None if note == INBOX[0] else OTHER, CALLER, 'POST', '23') for note in INBOX)
    definitions += ((SENT, CALLER, OTHER, 'REPORT', '8'), (FOREIGN, CALLER, OTHER, None, None),
                    (EXPIRED, OTHER, CALLER, None, None))
    literal = lambda value: 'NULL' if value is None else str(value)
    notes = ','.join('(' + f4.literal(note) + '::uuid,' + literal(sender) + ',' + str(recipient) + ',' + f4.literal(BODY)
                     + ',' + ('NULL' if context is None else f4.literal(context)) + ',' + literal(context_id)
                     + ',' + f4.literal(CREATED) + '::timestamptz,' + f4.literal('2000-01-01T00:00:00Z' if note == EXPIRED else EXPIRES) + '::timestamptz)'
                     for note, sender, recipient, context, context_id in definitions)
    mailboxes = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid,' + f4.literal('SENT' if note == SENT else 'INBOX')
                         + ',' + (f4.literal(READ_AT) + '::timestamptz' if note == INBOX[-1] else 'NULL') + ','
                         + ('true' if note in INBOX and (INBOX.index(note) + 1) % 2 == 0 else 'false') + ','
                         + f4.literal(MAILBOX_TIME) + '::timestamptz)' for member, note in MAILBOXES)
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + users + '; '
              'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES' + ','.join('(' + str(i) + ')' for i in MEMBERS) + '; '
              'INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,context_type,context_id,created_at,expires_at) VALUES' + notes + '; '
              'INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + mailboxes + '; COMMIT;')
    return OWNED


def cleanup_business_rows(owned):
    f2.require_hosted()
    if owned != OWNED:
        raise ValueError('F23 cleanup requires only the successfully inserted exact keys')
    pairs = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid)' for member, note in MAILBOXES)
    queries = ('DELETE FROM pawbridge_community.private_note_mailboxes WHERE (member_id,note_id) IN (' + pairs + ')',
               'DELETE FROM pawbridge_community.private_notes WHERE body=' + f4.literal(BODY) + ' AND note_id IN ('
               + ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES) + ')',
               'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + ','.join(map(str, MEMBERS)) + ')',
               'DELETE FROM pawbridge_user.users WHERE (user_id,email) IN (' + ','.join('(' + str(i) + ','
               + f4.literal('f23-' + str(i) + '@fixture.invalid') + ')' for i in USERS) + ')')
    for query in queries:
        require_owner()
        owned_sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.is_symlink() or path.resolve() != expected:
        raise SystemExit('F23 token path is not owned by this run')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F23 token file must remain regular 0600')
        tokens = json.loads(path.read_text())
        parts = tokens['F23_CALLER'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == CALLER
                 and claims['role'] == 'ROLE_USER' and type(claims['exp']) in (int, float)
                 and math.isfinite(claims['exp']) and claims['exp'] >= time.time() + 60)
    except (OSError, AttributeError, KeyError, IndexError, ValueError, TypeError):
        raise SystemExit('F23 used token identity or freshness is invalid') from None
    if not valid:
        raise SystemExit('F23 used token identity, role or final freshness differs')
    return {'F23_CALLER': tokens['F23_CALLER']}


def require_measurements(kind):
    f2.require_hosted()
    if kind == 'xml':
        if (not SEOUL_METHOD or any(type(n) is not int or n <= 0 for classes in REPORTS.values() for n in classes.values())
                or REPORTS[SEOUL_TASK] != {PG_CLASS: 1}):
            raise SystemExit('F23 original binding/Java case counts and Seoul method are unmeasured')
    elif kind == 'jar':
        if (not isinstance(EXPECTED_JAR_COUNTS, dict) or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
                or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
            raise SystemExit('F23 packaged request counts are unmeasured')
    else:
        raise ValueError('Unknown F23 measurement gate')


def verify_results():
    require_measurements('xml')
    counts = {}
    for task, classes in REPORTS.items():
        directory = ROOT / 'boot/build/test-results' / task
        if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in classes}:
            raise SystemExit('F23 compatibility requires its exact UTC/Seoul XML class set')
        counts[task] = {}
        for name, expected_count in classes.items():
            try:
                suite = ET.parse(directory / ('TEST-' + name + '.xml')).getroot()
                values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
            except (OSError, ET.ParseError, KeyError, ValueError):
                raise SystemExit('F23 report is missing or malformed') from None
            cases = suite.findall('testcase')
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != name or values['tests'] != expected_count
                    or len(cases) != expected_count or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(case.attrib.get('classname') != name for case in cases)
                    or any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped'))
                    or (task == SEOUL_TASK and cases[0].attrib.get('name') not in (SEOUL_METHOD, SEOUL_METHOD + '()'))):
                raise SystemExit('F23 requires actual successful cases and only its named Seoul timestamp case')
            counts[task][name] = expected_count
    return counts


def expected_detail(note):
    return {'noteId': note, 'body': BODY, 'direction': 'SENT' if note == SENT else 'INBOX', 'createdAt': CREATED,
            'readAt': READ_AT if note == INBOX[-1] else None,
            'favorite': note in INBOX and (INBOX.index(note) + 1) % 2 == 0,
            'counterpartId': None if note == INBOX[0] else OTHER,
            'counterpartNickname': '탈퇴한 회원' if note == INBOX[0] else NAMES[OTHER],
            'canReply': note in INBOX and note != INBOX[0],
            'contextHref': '/reports/8' if note == SENT else '/community/23'}


def expected_page(box='INBOX', favorite=False, page=0):
    ordered = [SENT] if box == 'SENT' else list(reversed(INBOX))
    if favorite:
        ordered = [note for note in ordered if note in INBOX and (INBOX.index(note) + 1) % 2 == 0]
    return {'content': [expected_detail(note) for note in ordered[page * 10:(page + 1) * 10]],
            'totalElements': len(ordered), 'totalPages': (len(ordered) + 9) // 10, 'number': page, 'size': 10}


def smoke():
    require_measurements('jar')
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('F23 packaged JAR missing')
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
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', timezone='Asia/Seoul', pool_maximum=4,
                  database_changes=0, s3_requests=0)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f23-packaged-startup.log', EVIDENCE / 'f23-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(query='', status=200, token='F23_CALLER', message=SUCCESS, layer='business', kind=None,
              method='GET', box='INBOX', favorite=False, page=0, suffix=''):
        headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
        before = snapshot()
        if token is not None: headers['Authorization'] = 'Bearer ' + load_tokens()[token]
        path = '/api/v1/notes' + suffix + query
        actual, raw, received = f3.request(base, path, method=method, headers=headers)
        if snapshot() != before: raise AssertionError('F23 request changed any business row or contacted S3')
        if (actual != status or received.get('Set-Cookie')
                or received.get('Cache-Control') != ('no-store' if layer == 'business' else f19.CACHE_CONTROL)):
            raise AssertionError('F23 request lost status/cache/cookie boundary')
        data = expected_detail(INBOX[-1]) if kind == 'detail_get' else expected_page(box, favorite, page)
        expected = ({'code': 200, 'message': SUCCESS, 'data': data} if status == 200 else
                    {'message': message} if layer == 'business' else {'code': status, 'message': message, 'data': None})
        response = json.loads(raw)
        if response != expected: raise AssertionError('F23 lost its exact original page/detail/error contract')
        if status == 200:
            rows = [response['data']] if kind == 'detail_get' else response['data']['content']
            if any(type(row['favorite']) is not bool or type(row['canReply']) is not bool
                   or (row['counterpartId'] is not None and type(row['counterpartId']) is not int) for row in rows):
                raise AssertionError('F23 row numeric/boolean JSON values differ from original types')
            if kind != 'detail_get' and any(type(response['data'][field]) is not int
                    for field in ('totalElements', 'totalPages', 'number', 'size')):
                raise AssertionError('F23 page numeric JSON types differ')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                     "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F23 request left an active DB transaction')
        if method == 'GET' and not suffix:
            result['list_get_checks'] += 1
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
                if database_snapshot() != baseline: raise AssertionError('F23 startup changed business rows')
                state = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:
                    raise AssertionError('F23 startup contacted S3')
                owned = prepare_business_rows()
                check()
                check('?page=1', page=1)
                check('?box=SENT', box='SENT')
                check('?favorites=true', favorite=True)
                check('?page=10000', page=10000)
                check('?page=-1&box=invalid', status=400, message='페이지 범위를 확인해 주세요.', kind='invalid_page')
                with denied_mailbox_time():
                    check(status=503, message=STORAGE_UNAVAILABLE, kind='storage_unavailable')
                check()
                check('?page=invalid', status=401, token=None, message='인증 토큰이 필요합니다.', layer='auth', kind='authentication')
                check(status=403, message='접근할 수 없습니다.', layer='route', kind='stream_denied', suffix='/stream/extra')
                # Exact POST is now assigned to note sending; deeper write routes remain denied.
                check(status=403, message='접근할 수 없습니다.', layer='route', kind='denied_write', method='POST', suffix='/unexposed/send')
                check(kind='detail_get', suffix='/' + INBOX[-1])
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F23 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F23 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline: raise AssertionError('F23 owned seed cleanup did not restore all sixteen tables')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F23 exceeded the one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F23 normal HTTP/two-pool shutdown evidence is missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, 'F23 synthetic', *(name.strip() for name in NAMES.values() if name.strip()), *tokens.values(),
                     *('f23-' + str(i) + '@fixture.invalid' for i in USERS))
        if any(value in text for value in forbidden): raise AssertionError('F23 log exposed credentials, tokens or synthetic private data')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F23 application connections remain after shutdown')
        if {key: result[key] for key in COUNTERS} != EXPECTED_JAR_COUNTS:
            raise AssertionError('F23 packaged request counts differ from the reviewed contract')
        result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f23-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F23 owned-mailbox-page journey and unchanged sixteen-table/S3 snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
