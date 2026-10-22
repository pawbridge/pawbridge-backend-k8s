#!/usr/bin/env python3
"""Owned hosted F26 socket journey. Import is inert; run receives the strict CI prefix.

Expected counters are a reviewed journey contract, never an execution receipt.
No real process, socket or database is started by preparing/importing this file.
"""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import http.client
import json
import math
import os
from pathlib import Path
import re
import socket
import stat
import threading
import time
from urllib.parse import urlsplit

CALLER, OTHER = 9980001, 9980002
USERS = (CALLER, OTHER)
INBOX, FAILURE, SENT = tuple('f2600000-0000-4000-8000-' + f'{n:012d}' for n in (1, 2, 3))
NOTE_IDS = (INBOX, FAILURE, SENT)
NAMES = {CALLER: 'F26caller9980001', OTHER: 'F26other9980002'}
BODY = 'F26 owned synthetic note'
STREAM = '/api/v1/notes/stream'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
STORAGE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
CACHE_CONTROL = 'no-cache, no-store, max-age=0, must-revalidate'
RESOURCES_STOPPED = 'Private note stream resources stopped'
RESOURCES_INCOMPLETE = 'Private note stream resources did not stop within budget'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'put_checks', 'successful_put_checks', 'rejected_put_checks',
            'get_checks', 'notification_checks', 'detail_checks', 'list_checks',
            'stream_open_checks', 'stream_rejected_checks', 'stream_ready_checks',
            'resync_checks', 'other_resync_checks', 'heartbeat_checks', 'short_expiry_checks',
            'storage_unavailable_checks', 'member_unavailable_checks', 'recovery_checks',
            'authentication_checks', 'adjacent_denied_checks', 'approved_database_write_checks')
# 16 REST requests + six stream handshakes (three successful, three rejected).
# Nonstreaming includes the three rejected SSE handshakes, whose body is finite JSON.
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (22, 19, 7, 5, 2, 15, 3, 2, 3, 3, 3, 3,
                                         5, 0, 1, 1, 1, 1, 2, 3, 1, 2)))
MAX_LINE, MAX_BYTES, MAX_EVENTS = 4096, 131072, 64
SOCKET_BUDGET, EVENT_BUDGET, JOIN_BUDGET = 90, 8, 2
JOURNEY_BUDGET = 180


def _decode(part):
    return base64.urlsafe_b64decode(part + '=' * (-len(part) % 4))


def _encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def _verified_token(token, identity, secret, now):
    """Verify actual issuer bytes too; crafted variants remain explicitly separate inputs."""
    try:
        parts = token.split('.')
        if len(parts) != 3 or not all(parts):
            raise ValueError()
        header, claims = json.loads(_decode(parts[0])), json.loads(_decode(parts[1]))
        digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[header['alg']]
        signature = hmac.new(secret.encode('utf-8'), (parts[0] + '.' + parts[1]).encode('ascii'), digest).digest()
        valid = (hmac.compare_digest(signature, _decode(parts[2])) and type(claims['userId']) is int
                 and claims['userId'] == identity and claims['role'] == 'ROLE_USER'
                 and type(claims['exp']) in (int, float) and math.isfinite(claims['exp'])
                 and claims['exp'] >= now + 60)
    except (AttributeError, KeyError, TypeError, ValueError, UnicodeError):
        raise SystemExit('F26 actual issuer identity/signature/freshness is invalid') from None
    if not valid:
        raise SystemExit('F26 actual issuer identity/signature/freshness differs')
    return header, claims


def load_tokens(ci):
    owner = ci.f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    try:
        mode = path.lstat().st_mode
        if path.is_symlink() or path.resolve() != expected or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise ValueError()
        values = json.loads(path.read_text())
        tokens = {name: values[name] for name in ('F26_CALLER', 'F26_OTHER')}
        secret = os.environ['F3_TEST_JWT_SECRET']
        if len(secret.encode('utf-8')) < 32:
            raise ValueError()
        for name, identity in (('F26_CALLER', CALLER), ('F26_OTHER', OTHER)):
            _verified_token(tokens[name], identity, secret, time.time())
    except (OSError, KeyError, TypeError, ValueError):
        raise SystemExit('F26 token input is not the owned regular 0600 issuer file') from None
    return tokens


def crafted_input(ci, kind):
    if kind not in ('short', 'no_exp', 'expired'):
        raise ValueError('F26 permits only three explicit crafted expiry inputs')
    normal = load_tokens(ci)['F26_CALLER']
    header, claims = _verified_token(normal, CALLER, os.environ['F3_TEST_JWT_SECRET'], time.time())
    claims = copy.deepcopy(claims)
    if kind == 'no_exp':
        claims.pop('exp')
    else:
        claims['exp'] = int(time.time()) + (5 if kind == 'short' else -5)
    parts = [_encode(json.dumps(value, separators=(',', ':')).encode('utf-8')) for value in (header, claims)]
    digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[header['alg']]
    parts.append(_encode(hmac.new(os.environ['F3_TEST_JWT_SECRET'].encode('utf-8'),
                                 '.'.join(parts).encode('ascii'), digest).digest()))
    return '.'.join(parts)  # Memory only: no file, mask command or log output.


def timestamp(value, wire=False):
    suffix = 'Z' if wire else r'(?:Z|[+-]\d{2}:\d{2})'
    if type(value) is not str or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?' + suffix, value):
        raise AssertionError('F26 timestamp is not a microsecond ISO Instant')
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)


def _literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def seed(ci):
    ci.require_fixture()
    users, notes = ','.join(map(str, USERS)), ','.join(_literal(n) + '::uuid' for n in NOTE_IDS)
    query = ('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + users + ')) + '
             '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + ')) + '
             '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + ')) + '
             '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE note_id IN (' + notes + ') OR sender_id IN (' + users
             + ') OR recipient_id IN (' + users + ')) + (SELECT count(*) FROM ' + ci.MAILBOXES
             + ' WHERE member_id IN (' + users + ') OR note_id IN (' + notes + ')) + '
             '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
             + ') OR blocked_id IN (' + users + '))')
    if ci.owned_sql(query) != '0':
        raise SystemExit('F26 refuses nonempty reserved User/member/note/reference keys')
    now = datetime.now(timezone.utc)
    created = (now - timedelta(minutes=1)).replace(microsecond=123456).isoformat().replace('+00:00', 'Z')
    expires = (now + timedelta(hours=1)).replace(microsecond=123456).isoformat().replace('+00:00', 'Z')
    user_rows = ','.join('(' + str(i) + ',' + _literal(NAMES[i]) + ',' + _literal('f26-' + str(i) + '@fixture.invalid')
                         + ",'F26 synthetic','GOOGLE','ROLE_USER')" for i in USERS)
    note_rows = ','.join('(' + _literal(n) + '::uuid,' + str(CALLER if n == SENT else OTHER) + ','
                         + str(OTHER if n == SENT else CALLER) + ',' + _literal(BODY) + ','
                         + _literal(created) + '::timestamptz,' + _literal(expires) + '::timestamptz)' for n in NOTE_IDS)
    boxes = ','.join('(' + str(CALLER) + ',' + _literal(n) + '::uuid,' + _literal('SENT' if n == SENT else 'INBOX')
                     + ',NULL,false,' + _literal(created) + '::timestamptz)' for n in NOTE_IDS)
    ci.owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + user_rows + '; '
                 'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(' + str(CALLER) + '),(' + str(OTHER) + '); '
                 'INSERT INTO ' + ci.NOTES + '(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES' + note_rows + '; '
                 'INSERT INTO ' + ci.MAILBOXES + '(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + boxes + '; COMMIT;')
    return {'users': USERS, 'notes': NOTE_IDS, 'created': created}


def cleanup(ci, owned):
    """Reobserve the namespace even after an HTTP assertion failed after a real commit."""
    if owned.get('users') != USERS or owned.get('notes') != NOTE_IDS:
        raise ValueError('F26 cleanup requires acknowledged exact atomic seed ownership')
    ci.require_owner()
    users = ','.join(map(str, USERS))
    notes = ','.join(_literal(n) + '::uuid' for n in NOTE_IDS)
    state = ci.database_snapshot()
    seeded_notes = [r for r in state['private_notes'] if r['note_id'] in NOTE_IDS]
    seeded_boxes = [r for r in state['private_note_mailboxes'] if r['note_id'] in NOTE_IDS or r['member_id'] in USERS]
    if (len(seeded_notes) != 3 or any(r['body'] != BODY or r['sender_id'] != (CALLER if r['note_id'] == SENT else OTHER)
            or r['recipient_id'] != (OTHER if r['note_id'] == SENT else CALLER) for r in seeded_notes)
            or len(seeded_boxes) != 3 or {(r['member_id'], r['note_id']) for r in seeded_boxes} != {(CALLER, n) for n in NOTE_IDS}):
        raise SystemExit('F26 cleanup refuses altered or unowned note/mailbox identities')
    if ci.owned_sql('SELECT (SELECT count(*) FROM ' + ci.NOTES + ' WHERE (sender_id IN (' + users
            + ') OR recipient_id IN (' + users + ')) AND note_id NOT IN (' + notes + ')) + '
            '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE reply_to IN (' + notes + ') AND note_id NOT IN (' + notes + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
            + ') OR blocked_id IN (' + users + ')) + (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + '))') != '0':
        raise SystemExit('F26 cleanup refuses unexpected note/block/deletion references')
    if ci.owned_sql('SELECT count(*) FROM pawbridge_user.users WHERE (user_id,email,nickname) IN ('
            + ','.join('(' + str(i) + ',' + _literal('f26-' + str(i) + '@fixture.invalid') + ',' + _literal(NAMES[i]) + ')' for i in USERS) + ')') != '2':
        raise SystemExit('F26 cleanup refuses changed owned User identities')
    ci.require_owner()
    ci.owned_sql('BEGIN; DELETE FROM ' + ci.MAILBOXES + ' WHERE member_id=' + str(CALLER) + ' AND note_id IN (' + notes + '); '
                 'DELETE FROM ' + ci.NOTES + ' WHERE body=' + _literal(BODY) + ' AND note_id IN (' + notes + '); '
                 'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + '); '
                 'DELETE FROM pawbridge_user.users WHERE user_id IN (' + users + '); COMMIT;')


def expected_delta(before, after, note, started, finished):
    expected = copy.deepcopy(before)
    rows = [r for r in expected['private_note_mailboxes'] if r['member_id'] == CALLER and r['note_id'] == note]
    found = [r for r in after['private_note_mailboxes'] if r['member_id'] == CALLER and r['note_id'] == note]
    if len(rows) != 1 or len(found) != 1 or rows[0]['direction'] != 'INBOX' or rows[0]['read_at'] is not None:
        raise AssertionError('F26 first-read delta requires one previously unread owned INBOX')
    instant = timestamp(found[0]['read_at'])
    if not started - timedelta(seconds=1) <= instant <= finished + timedelta(seconds=1):
        raise AssertionError('F26 read_at falls outside the actual request clock window')
    rows[0]['read_at'] = found[0]['read_at']
    if after != expected:
        raise AssertionError('F26 request changed more than its one owned read_at cell')
    return found[0]['read_at']


class Stream:
    """One bounded real HTTP stream; only event counts cross the reader-thread boundary."""
    def __init__(self, base, headers):
        url = urlsplit(base)
        if url.scheme != 'http' or url.hostname != '127.0.0.1' or not url.port or url.path:
            raise ValueError('F26 SSE must use only the owned loopback listener')
        self.connection = http.client.HTTPConnection(url.hostname, url.port, timeout=SOCKET_BUDGET)
        self.socket = self.response = self.thread = None
        self.condition = threading.Condition()
        self.events = dict.fromkeys(('ready', 'resync', 'heartbeat'), 0)
        self.bytes = 0
        self.eof = self.failed = self.closed = False
        try:
            self.connection.request('GET', STREAM, headers=dict(headers, Accept='text/event-stream'))
            self.socket = self.connection.sock
            self.response = self.connection.getresponse()
            if (self.response.status != 200 or self.response.getheader('Set-Cookie')
                    or self.response.getheader('Cache-Control') != 'no-store'
                    or self.response.getheader('X-Accel-Buffering') != 'no'
                    or (self.response.getheader('Content-Type') or '').split(';')[0] != 'text/event-stream'):
                raise AssertionError('F26 SSE status/type/cache/cookie/buffering differs')
            self.thread = threading.Thread(target=self._read, name='f26-owned-sse-reader', daemon=True)
            self.thread.start()
        except BaseException:
            self.close()
            raise

    def _read(self):
        event = None
        data = []
        try:
            while True:
                line = self.response.readline(MAX_LINE + 1)
                if not line:
                    if event is not None or data:
                        raise ValueError()
                    with self.condition:
                        self.eof = True
                        self.condition.notify_all()
                    return
                self.bytes += len(line)
                if len(line) > MAX_LINE or self.bytes > MAX_BYTES or not line.endswith(b'\n'):
                    raise ValueError()
                value = line.rstrip(b'\r\n').decode('utf-8')
                if not value:
                    if event is None and not data:
                        continue
                    if event not in self.events or data != ['{}']:
                        raise ValueError()
                    with self.condition:
                        if sum(self.events.values()) >= MAX_EVENTS:
                            raise ValueError()
                        self.events[event] += 1
                        self.condition.notify_all()
                    event, data = None, []
                elif value.startswith('event:') and event is None:
                    event = value[6:].removeprefix(' ')
                elif value.startswith('data:'):
                    data.append(value[5:].removeprefix(' '))
                else:
                    raise ValueError()
        except Exception:
            with self.condition:
                if not self.closed:
                    self.failed = True
                self.condition.notify_all()

    def wait(self, event, count=1, timeout=EVENT_BUDGET, *, at_least=False):
        deadline = time.monotonic() + timeout
        with self.condition:
            while not self.failed and not self.eof and self.events[event] < count:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.condition.wait(remaining)
            if self.failed or self.events[event] < count or (not at_least and self.events[event] != count):
                raise AssertionError('F26 required bounded SSE event count differs')

    def wait_eof(self, timeout=EVENT_BUDGET):
        deadline = time.monotonic() + timeout
        with self.condition:
            while not self.eof and not self.failed:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.condition.wait(remaining)
            if not self.eof or self.failed:
                raise AssertionError('F26 stream did not finish with a clean bounded server EOF')

    def assert_resync(self, expected):
        with self.condition:
            if self.failed or self.events['resync'] != expected:
                raise AssertionError('F26 unexpected own/other resync count')

    def close(self):
        self.closed = True
        if self.socket is not None:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.connection.close()
        if self.thread is not None:
            self.thread.join(JOIN_BUDGET)
            if self.thread.is_alive():
                raise AssertionError('F26 owned SSE reader did not terminate within its join budget')
        if self.response is not None:
            self.response.close()


def close_streams(streams):
    failed = False
    for stream in streams:
        try:
            stream.close()
        except Exception:
            failed = True
    if failed:
        raise AssertionError('F26 at least one owned SSE reader failed bounded cleanup')


def _view(note, created, read_at):
    return dict(noteId=note, body=BODY, direction='SENT' if note == SENT else 'INBOX', createdAt=created,
                readAt=read_at, favorite=False, counterpartId=OTHER, counterpartNickname=NAMES[OTHER],
                canReply=note != SENT, contextHref=None)


def verify_view(body, kind, reads, created, note=INBOX):
    if kind == 'notifications':
        wanted = dict(content=[dict(noteId=n, kind='PRIVATE_NOTE', actorId=OTHER, actorNickname=NAMES[OTHER],
                                    createdAt=created, read=reads[n] is not None, href='/notes/' + n)
                               for n in (FAILURE, INBOX)], nextCursor=None,
                      unreadCount=sum(reads[n] is None for n in (INBOX, FAILURE)))
    elif kind == 'detail':
        wanted = _view(note, created, reads[note])
    elif kind == 'list':
        wanted = dict(content=[_view(n, created, reads[n]) for n in (FAILURE, INBOX)],
                      totalElements=2, totalPages=1, number=0, size=10)
    elif kind == 'sent_list':
        wanted = dict(content=[_view(SENT, created, None)], totalElements=1, totalPages=1, number=0, size=10)
    else:
        raise ValueError('F26 unknown REST projection')
    # Normalize only timestamps after proving their wire format and instant equivalence.
    actual = copy.deepcopy(body)
    data = actual.get('data')
    if not isinstance(data, dict):
        raise AssertionError('F26 exact REST envelope is absent')
    items = [data] if kind == 'detail' else data.get('content')
    if not isinstance(items, list):
        raise AssertionError('F26 exact REST content shape differs')
    for item in items:
        if type(item.get('noteId')) is not str or type(item.get('actorId' if kind == 'notifications' else 'counterpartId')) is not int:
            raise AssertionError('F26 REST identifiers have unexpected wire types')
        if kind == 'notifications':
            if type(item.get('read')) is not bool:
                raise AssertionError('F26 notification read must remain boolean')
        elif type(item.get('favorite')) is not bool or type(item.get('canReply')) is not bool:
            raise AssertionError('F26 detail flags must remain boolean')
        if timestamp(item['createdAt'], wire=True) != timestamp(created, wire=True):
            raise AssertionError('F26 REST createdAt shifted')
        item['createdAt'] = created
        if kind != 'notifications':
            saved = reads[item['noteId']]
            if saved is not None:
                if timestamp(item['readAt'], wire=True) != timestamp(saved):
                    raise AssertionError('F26 REST readAt differs from actual committed DB value')
                item['readAt'] = saved
    if actual != {'code': 200, 'message': SUCCESS, 'data': wanted}:
        raise AssertionError('F26 exact REST projection/read/unread value differs')
    numeric = ('unreadCount',) if kind == 'notifications' else () if kind == 'detail' else ('totalElements', 'totalPages', 'number', 'size')
    if type(actual.get('code')) is not int or any(type(data.get(k)) is not int for k in numeric):
        raise AssertionError('F26 REST counts must remain integer JSON values')


def _app_environment(ci, bucket, prefix):
    base = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if k in os.environ}
    env = dict(base, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=ci.f3.APP,
               PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
               PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], PAWBRIDGE_STORAGE_BUCKET=bucket,
               PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
               PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
               PAWBRIDGE_STORAGE_REGION='us-east-1')
    return base, env


def run(ci):
    owner = ci.require_fixture()
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', owner=owner, source_sha=ci.SOURCE, runtime_sha=os.environ.get('GITHUB_SHA'),
                  timezone='Asia/Seoul', pool_maximum=4,
                  protected_database_changes=0, s3_requests=0,
                  token_evidence='normal actual issuer; short/no-exp/expired crafted in memory',
                  permitted_delta='only two owned INBOX mailbox read_at cells, first value retained')
    ci.EVIDENCE.mkdir(parents=True, exist_ok=True)
    receipt = ci.EVIDENCE / 'f26-packaged-result.txt'
    streams, tokens, secrets, owned = [], {}, [], None
    app = stub = None
    app_path, stub_path = ci.EVIDENCE / 'f26-packaged-startup.log', ci.EVIDENCE / 'f26-s3-stub.log'
    baseline = None
    try:
        ci.require_measurements('jar')
        if tuple(ci.COUNTERS) != COUNTERS or ci.EXPECTED_JAR_COUNTS != EXPECTED_JAR_COUNTS:
            raise SystemExit('F26 runtime and prefix counter contracts differ')
        if not re.fullmatch('[0-9a-f]{40}', result['runtime_sha'] or ''):
            raise SystemExit('F26 runtime source commit is absent')
        jar = ci.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
        if not jar.is_file():
            raise SystemExit('F26 packaged application is absent')
        tokens = load_tokens(ci)
        secrets.extend(tokens.values())
        baseline = ci.database_snapshot()
        bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
        base_env, app_env = _app_environment(ci, bucket, prefix)
        controls = {'X-F3-Owner': owner}
        reads = dict.fromkeys(NOTE_IDS)
        deadline = time.monotonic() + JOURNEY_BUDGET

        def snapshot():
            if time.monotonic() >= deadline:
                raise AssertionError('F26 bounded runtime journey deadline exceeded')
            state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
            return ci.database_snapshot(), state['counts'], state['objects'], len(state['requests'])

        def no_transaction():
            if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('F26 open stream or completed request retained a database transaction')

        def check(path, *, method='GET', status=200, token=True, crafted=None,
                  message=None, security=False, view=None, delta=None, kind=None):
            before = snapshot()
            headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
            if token:
                headers['Authorization'] = 'Bearer ' + (crafted if crafted is not None else load_tokens(ci)['F26_CALLER'])
            if crafted is not None:
                headers['X-Auth-Expires-At'] = str(int(time.time()) + 3600)
            started = datetime.now(timezone.utc)
            actual, raw, received = ci.f3.request(base, path, method=method, headers=headers)
            finished = datetime.now(timezone.utc)
            after = snapshot()
            if delta is not None:
                reads[delta] = expected_delta(before[0], after[0], delta, started, finished)
            elif after[0] != before[0]:
                raise AssertionError('F26 request changed protected database rows')
            if after[1:] != before[1:]:
                raise AssertionError('F26 request contacted or changed S3')
            if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != (CACHE_CONTROL if security else 'no-store'):
                raise AssertionError('F26 REST status/cache/cookie differs')
            if status == 200 and method == 'PUT':
                if raw:
                    raise AssertionError('F26 read mutation must return an empty original 200')
            elif view is not None:
                verify_view(json.loads(raw), view, reads, owned['created'])
            elif json.loads(raw) != ({'code': status, 'message': message, 'data': None} if security else {'message': message}):
                raise AssertionError('F26 exact error envelope differs')
            no_transaction()
            result['request_checks'] += 1
            result['nonstreaming_checks'] += 1
            result['put_checks' if method == 'PUT' else 'get_checks'] += 1
            if method == 'PUT':
                result['successful_put_checks' if status == 200 else 'rejected_put_checks'] += 1
            if kind:
                result[kind + '_checks'] += 1
            if delta is not None:
                result['approved_database_write_checks'] += 1

        def opening(identity, crafted_kind=None):
            before = snapshot()
            token = crafted_input(ci, crafted_kind) if crafted_kind is not None else load_tokens(ci)['F26_CALLER' if identity == CALLER else 'F26_OTHER']
            if crafted_kind is not None:
                secrets.append(token)
            stream = Stream(base, {'Authorization': 'Bearer ' + token, 'X-User-Id': str(OTHER if identity == CALLER else CALLER),
                                   'X-Auth-Expires-At': str(int(time.time()) + 3600)})
            streams.append(stream)
            stream.wait('ready')
            if snapshot() != before:
                raise AssertionError('F26 SSE opening changed database rows or S3')
            no_transaction()
            result['stream_open_checks'] += 1
            result['stream_ready_checks'] += 1
            result['request_checks'] += 1
            result['get_checks'] += 1
            return stream

        def resync(stream, count):
            stream.wait('resync', count)
            other.assert_resync(0)
            no_transaction()
            result['resync_checks'] += 1

        with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
            try:
                app, stub, base, endpoint, attempts, offset = ci.f4.start_ready_children(jar, app_env, base_env, owner,
                    bucket, prefix, stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                if ci.database_snapshot() != baseline:
                    raise AssertionError('F26 startup changed business rows')
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:
                    raise AssertionError('F26 startup contacted S3')
                owned = seed(ci)
                caller, other = opening(CALLER), opening(OTHER)
                check('/api/v1/notes/notifications', view='notifications', kind='notification')
                path = '/api/v1/notes/' + INBOX + '/read'
                check(path, method='PUT', delta=INBOX)
                resync(caller, 1)
                check('/api/v1/notes/notifications', view='notifications', kind='notification')
                check('/api/v1/notes/' + INBOX, view='detail', kind='detail')
                check('/api/v1/notes', view='list', kind='list')
                check(path, method='PUT')  # First read_at retained, but repeat commit still resyncs.
                resync(caller, 2)
                check('/api/v1/notes/' + INBOX, view='detail', kind='detail')
                check('/api/v1/notes/' + SENT + '/read', method='PUT')
                resync(caller, 3)
                check('/api/v1/notes?box=SENT', view='sent_list', kind='list')
                failure = '/api/v1/notes/' + FAILURE + '/read'
                with ci.denied_privilege('read'):
                    check(failure, method='PUT', status=503, message=STORAGE, kind='storage_unavailable')
                caller.assert_resync(3)
                check(failure, method='PUT', delta=FAILURE, kind='recovery')
                resync(caller, 4)
                with ci.denied_privilege('nickname'):
                    check(path, method='PUT', status=503, message=MEMBER_UNAVAILABLE, kind='member_unavailable')
                caller.assert_resync(4)
                check(path, method='PUT', kind='recovery')
                resync(caller, 5)
                check('/api/v1/notes/notifications', view='notifications', kind='notification')
                check('/api/v1/notes', view='list', kind='list')
                # Real heartbeat/expiry are thin socket checks; queue/write-lock limits belong to unit tests.
                caller.wait('heartbeat', 1, timeout=18, at_least=True)
                other.wait('heartbeat', 1, timeout=18, at_least=True)
                result['heartbeat_checks'] += 1
                expiring = opening(CALLER, 'short')  # Crafted after snapshot, immediately before connection.
                expiring.wait_eof(timeout=8)
                expiring.assert_resync(0)
                result['short_expiry_checks'] += 1
                for kind, message in (('no_exp', '토큰 파싱에 실패했습니다.'), ('expired', '유효하지 않거나 만료된 토큰입니다.')):
                    token = crafted_input(ci, kind)
                    secrets.append(token)
                    check(STREAM, status=401, crafted=token, security=True, message=message, kind='authentication')
                    result['stream_rejected_checks'] += 1
                check(STREAM, status=401, token=False, security=True, message='인증 토큰이 필요합니다.', kind='authentication')
                result['stream_rejected_checks'] += 1
                check(STREAM + '/extra', status=403, security=True, message='접근할 수 없습니다.', kind='adjacent_denied')
                caller.assert_resync(5)
                other.assert_resync(0)
                ci.require_fixture()
                # Deliberately leave the two normal SSE sockets open until server SIGTERM.
                result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F26 packaged application')
                app = None
                caller.wait_eof()
                other.wait_eof()
                caller.assert_resync(5)
                other.assert_resync(0)
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:
                    raise AssertionError('F26 shutdown contacted or changed S3')
                result['server_closed_open_streams'] = 2
            finally:
                try:
                    if app is not None:
                        result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F26 packaged application')
                finally:
                    try:
                        close_streams(streams)
                    finally:
                        try:
                            if stub is not None:
                                result['stub_sigterm_exit'] = ci.f3.stop_owned_child(stub, 'F26 S3 stub')
                        finally:
                            if owned is not None:
                                cleanup(ci, owned)
        if ci.database_snapshot() != baseline:
            raise AssertionError('F26 owned cleanup did not restore all sixteen original tables')
        ci.require_fixture()
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F26 child log budget exceeded')
        text, stub_text = ci.f4.fresh_log(app_path, offset), stub_path.read_text()
        if (text.count('Start completed.') != 1 or text.count('Shutdown completed.') != 1 or 'Graceful shutdown complete' not in text
                or text.count(RESOURCES_STOPPED) != 1 or RESOURCES_INCOMPLETE in text
                or result.get('server_closed_open_streams') != 2):
            raise AssertionError('F26 opened-SSE resource/single-pool normal shutdown evidence is incomplete')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, 'F26 synthetic', *secrets, *NAMES.values(), *('f26-' + str(i) + '@fixture.invalid' for i in USERS))
        all_app_text = app_path.read_text()
        if any(v and (v in all_app_text or v in stub_text) for v in forbidden):
            raise AssertionError('F26 child log exposed a private input')
        if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F26 application connections remain after shutdown')
        if {k: result[k] for k in COUNTERS} != EXPECTED_JAR_COUNTS:
            raise AssertionError('F26 exact packaged journey counts differ')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0,
                      stream_resources_stopped=True, owned_cleanup_restored=True)
    finally:
        receipt.write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F26 read/resync/REST/expiry/opened-SSE shutdown journey passed')
