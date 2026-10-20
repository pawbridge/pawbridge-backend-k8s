#!/usr/bin/env python3
"""Prepared F29 send journey. Import is inert; expected counts are not execution evidence."""
import calendar
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid

import f26_stream_probe as transport
import f28_stream_probe as prior

CALLER, OTHER = 9983001, 9983002
USERS = (CALLER, OTHER)
NAMES = {CALLER: ' F29sender9983001 ', OTHER: 'F29recipient9983002'}
BODY = 'F29 owned synthetic note 🦊'
RAW_BODY = ' \n' + BODY + ' \t'
REQUEST_IDS = tuple('f2900000-0000-4000-8000-' + f'{n:012d}' for n in (101, 102, 103))
SUCCESS = prior.SUCCESS
CONFLICT = '같은 전송 키로 다른 내용을 보낼 수 없습니다.'
JOURNEY_BUDGET = 180
COUNTERS = ('request_checks', 'nonstreaming_checks', 'post_checks', 'successful_post_checks',
            'rejected_post_checks', 'get_checks', 'stream_open_checks', 'stream_ready_checks',
            'recipient_note_checks', 'sender_note_checks', 'resync_checks', 'replay_checks',
            'conflict_checks', 'storage_unavailable_checks', 'member_unavailable_checks',
            'recovery_checks', 'authentication_checks', 'adjacent_denied_checks',
            'notification_checks', 'detail_checks', 'list_checks', 'approved_database_write_checks',
            'inserted_note_rows', 'inserted_mailbox_rows', 'inserted_request_rows')
# Planned HTTP15 = POST9(success4/reject5) + GET6(SSE2/REST4). No runtime has passed yet.
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (15, 13, 9, 4, 5, 6, 2, 2, 3, 0, 0, 1, 1, 1, 1, 2, 1, 1, 1, 1, 2, 3, 3, 6, 3)))
literal = prior.literal


def instant_ns(value, wire=False):
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)', value or '')
    if not match or (wire and match[3] != 'Z'):
        raise AssertionError('F29 timestamp is not an ISO Instant')
    date = datetime.fromisoformat(match[1] + match[3].replace('Z', '+00:00')).astimezone(timezone.utc)
    delta = date - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return (delta.days * 86400 + delta.seconds) * 1_000_000_000 + int((match[2] or '').ljust(9, '0') or '0')


def same_instant(raw, stored):
    if abs(instant_ns(raw, wire=True) - instant_ns(stored)) > 1000:
        raise AssertionError('F29 wire and stored instants differ beyond PostgreSQL microsecond precision')


def request_hash():
    return hashlib.sha256((str(OTHER) + '\nnull\nnull\nnull\n' + BODY).encode()).hexdigest()


def load_tokens(ci):
    owner = ci.f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    wanted = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    try:
        mode = path.lstat().st_mode
        if path.is_symlink() or path.resolve() != wanted or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise ValueError()
        values = json.loads(path.read_text())
        tokens = {name: values[name] for name in ('F29_CALLER', 'F29_OTHER')}
        secret = os.environ['F3_TEST_JWT_SECRET']
        if len(secret.encode()) < 32:
            raise ValueError()
        for name, identity in (('F29_CALLER', CALLER), ('F29_OTHER', OTHER)):
            prior._claims(tokens[name], identity, secret)  # Existing signature/role/TTL60 contract, no crafted tokens.
    except (OSError, KeyError, TypeError, ValueError, SystemExit):
        raise SystemExit('F29 requires its two normal tokens in the owned regular 0600 issuer file') from None
    return tokens


class NoteStream(transport.Stream):
    """Only the event decoder differs; connection/auth/wait/close and all budgets stay F26-owned."""
    def __init__(self, base, headers):
        self.notes = []
        super().__init__(base, headers)

    def _read(self):
        self.events['note'] = 0
        event, data = None, []
        try:
            while True:
                line = self.response.readline(transport.MAX_LINE + 1)
                if not line:
                    if event is not None or data:
                        raise ValueError()
                    with self.condition:
                        self.eof = True; self.condition.notify_all()
                    return
                self.bytes += len(line)
                if len(line) > transport.MAX_LINE or self.bytes > transport.MAX_BYTES or not line.endswith(b'\n'):
                    raise ValueError()
                value = line.rstrip(b'\r\n').decode('utf-8')
                if not value:
                    if event is None and not data:
                        continue
                    if event not in self.events or len(data) != 1:
                        raise ValueError()
                    payload = json.loads(data[0])
                    if event == 'note':
                        validate_notification(payload)
                    elif payload != {}:
                        raise ValueError()
                    with self.condition:
                        if sum(self.events.values()) >= transport.MAX_EVENTS:
                            raise ValueError()
                        if event == 'note': self.notes.append(payload)
                        self.events[event] += 1; self.condition.notify_all()
                    event, data = None, []
                elif value.startswith('event:') and event is None:
                    event = value[6:].removeprefix(' ')
                elif value.startswith('data:'):
                    data.append(value[5:].removeprefix(' '))
                else:
                    raise ValueError()
        except Exception:
            with self.condition:
                if not self.closed: self.failed = True
                self.condition.notify_all()

    def assert_notes(self, records):
        with self.condition:
            if self.failed or self.events['note'] != len(records) or len(self.notes) != len(records):
                raise AssertionError('F29 recipient/sender note event count differs')
            for payload, record in zip(self.notes, records):
                validate_notification(payload, record)


def validate_notification(value, record=None):
    if (type(value) is not dict or set(value) != {'noteId', 'kind', 'actorId', 'actorNickname', 'createdAt', 'read', 'href'}
            or type(value['actorId']) is not int or value['actorId'] != CALLER
            or value['kind'] != 'PRIVATE_NOTE' or value['actorNickname'] != NAMES[CALLER]
            or value['read'] is not False):
        raise AssertionError('F29 notification must contain only the seven original fields')
    identity = str(uuid.UUID(value['noteId']))
    if identity != value['noteId'] or value['href'] != '/notes/' + identity:
        raise AssertionError('F29 notification identity is inconsistent')
    instant_ns(value['createdAt'], wire=True)
    if record is not None:
        if identity != record['note']['note_id']:
            raise AssertionError('F29 notification and committed identity differ')
        same_instant(value['createdAt'], record['note']['created_at'])


def seed(ci):
    ci.require_fixture()
    users = ','.join(map(str, USERS)); keys = ','.join(literal(k) + '::uuid' for k in REQUEST_IDS)
    if ci.owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + ')) + '
            '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE sender_id IN (' + users + ') OR recipient_id IN (' + users + ')) + '
            '(SELECT count(*) FROM ' + ci.MAILBOXES + ' WHERE member_id IN (' + users + ')) + '
            '(SELECT count(*) FROM ' + ci.REQUESTS + ' WHERE sender_id IN (' + users + ') OR request_id IN (' + keys + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users + ') OR blocked_id IN (' + users + '))') != '0':
        raise SystemExit('F29 refuses occupied send fixture identities')
    rows = ','.join('(' + str(i) + ',' + literal(NAMES[i]) + ',' + literal('f29-' + str(i) + '@fixture.invalid')
                    + ",'F29 synthetic','GOOGLE','ROLE_USER')" for i in USERS)
    ci.owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + rows
                 + '; INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(' + str(CALLER) + '),(' + str(OTHER) + '); COMMIT;')
    return {'users': USERS, 'ack': {}}


def committed_records(state, owned):
    """Discover only complete owned request→note→two-mailbox commits, never claim orphan partial writes."""
    if owned.get('users') != USERS:
        raise ValueError('F29 ownership acknowledgement differs')
    records = {}
    for key in REQUEST_IDS:
        requests = [r for r in state['private_note_requests'] if r['sender_id'] == CALLER and r['request_id'] == key]
        if not requests: continue
        if len(requests) != 1: raise AssertionError('F29 duplicate owned request key')
        request = requests[0]; identity = str(uuid.UUID(request['note_id']))
        notes = [n for n in state['private_notes'] if n['note_id'] == identity]
        boxes = [m for m in state['private_note_mailboxes'] if m['note_id'] == identity]
        if (set(request) != {'sender_id', 'request_id', 'request_hash', 'note_id', 'created_at'}
                or request['request_hash'] != request_hash() or len(notes) != 1 or len(boxes) != 2):
            raise AssertionError('F29 owned request is not a complete original send')
        note = notes[0]
        if (set(note) != {'note_id', 'sender_id', 'recipient_id', 'body', 'reply_to', 'context_type', 'context_id', 'created_at', 'expires_at'}
                or note['sender_id'] != CALLER or note['recipient_id'] != OTHER or note['body'] != BODY
                or any(note[k] is not None for k in ('reply_to', 'context_type', 'context_id'))
                or instant_ns(request['created_at']) != instant_ns(note['created_at'])):
            raise AssertionError('F29 generated note does not match the owned send specification')
        date = datetime.fromisoformat(note['created_at'].replace('Z', '+00:00')).astimezone(timezone.utc)
        year = date.year + 1
        expiry = date.replace(year=year, day=min(date.day, calendar.monthrange(year, date.month)[1]))
        if abs(instant_ns(note['expires_at']) - instant_ns(expiry.isoformat())) > 1000:
            raise AssertionError('F29 note expiry is not one UTC calendar year')
        if {m['member_id'] for m in boxes} != set(USERS):
            raise AssertionError('F29 note contains an unowned mailbox')
        for m in boxes:
            sent = m['member_id'] == CALLER
            if (set(m) != {'member_id', 'note_id', 'direction', 'read_at', 'favorite', 'created_at'}
                    or m['direction'] != ('SENT' if sent else 'INBOX') or m['favorite'] is not False
                    or instant_ns(m['created_at']) != instant_ns(note['created_at'])
                    or (instant_ns(m['read_at']) != instant_ns(note['created_at']) if sent else m['read_at'] is not None)):
                raise AssertionError('F29 committed mailbox metadata differs')
        records[key] = dict(note=note, mailboxes=boxes, request=request)
    identities = {r['note']['note_id'] for r in records.values()}
    if (len(identities) != len(records)
            or any(n['note_id'] not in identities for n in state['private_notes'] if n['sender_id'] in USERS or n['recipient_id'] in USERS)
            or any(n['reply_to'] in identities for n in state['private_notes'])
            or any(r['request_id'] not in records or r['sender_id'] != CALLER for r in state['private_note_requests'] if r['sender_id'] in USERS or r['note_id'] in identities)
            or any(m['note_id'] not in identities for m in state['private_note_mailboxes'] if m['member_id'] in USERS)):
        raise AssertionError('F29 send ownership contains partial or foreign references')
    if any(key not in records or records[key]['note']['note_id'] != note for key, note in owned['ack'].items()):
        raise AssertionError('F29 acknowledged send identity disappeared or changed')
    return records


def send_delta(before, after, owned, key, started, finished):
    records = committed_records(after, owned)
    if key not in records or any(r['request_id'] == key and r['sender_id'] == CALLER for r in before['private_note_requests']):
        raise AssertionError('F29 new send requires one previously absent request key')
    record = records[key]; note = record['note']
    if not started - 1000 <= instant_ns(note['created_at']) <= finished + 1000:
        raise AssertionError('F29 generated creation instant is outside the request interval')
    expected = copy.deepcopy(before)
    for table, rows, order in (('private_notes', [note], lambda r: r['note_id']),
                               ('private_note_mailboxes', record['mailboxes'], lambda r: (r['member_id'], r['note_id'])),
                               ('private_note_requests', [record['request']], lambda r: (r['sender_id'], r['request_id']))):
        expected[table].extend(copy.deepcopy(rows)); expected[table].sort(key=order)
    if expected != after:
        raise AssertionError('F29 send changed more than one note, two mailboxes and one request')
    return record


def acknowledge(owned, key, response, record, recipient, sender):
    identity = record['note']['note_id']
    if response != {'code': 200, 'message': SUCCESS, 'data': {'noteId': identity}}:
        raise AssertionError('F29 response and committed send identity differ')
    pending = list(owned['ack']) + [key]
    recipient.wait('note', len(pending))
    existing = getattr(recipient, 'acknowledged_records', [])
    recipient.assert_notes(existing + [record]); sender.assert_notes([])
    recipient.assert_resync(0); sender.assert_resync(0)
    owned['ack'][key] = identity  # Register only after response, complete DB and recipient-only SSE agree.
    recipient.acknowledged_records = existing + [record]


def cleanup(ci, owned):
    ci.require_owner()
    state = ci.database_snapshot(); records = committed_records(state, owned)
    users = ','.join(map(str, USERS))
    if (any(r['user_id'] in USERS for r in state['contact_deletions'])
            or any(r['member_id'] in USERS or r['blocked_id'] in USERS for r in state['private_note_blocks'])
            or {(r['user_id'], r['email'], r['nickname']) for r in state['users'] if r['user_id'] in USERS}
               != {(i, 'f29-' + str(i) + '@fixture.invalid', NAMES[i]) for i in USERS}):
        raise SystemExit('F29 refuses cleanup of modified or externally referenced fixture users')
    identities = [r['note']['note_id'] for r in records.values()]
    requests = ','.join(literal(k) + '::uuid' for k in records) or 'NULL'
    notes = ','.join(literal(n) + '::uuid' for n in identities) or 'NULL'
    # A response/SSE failure can leave a committed send. Recover by exact request+full-row ownership,
    # never by guessing generated UUIDs or promoting those rows to successful ACKs.
    ci.require_owner()
    ci.owned_sql('BEGIN; DELETE FROM ' + ci.REQUESTS + ' WHERE sender_id=' + str(CALLER) + ' AND request_id IN (' + requests + '); '
        'DELETE FROM ' + ci.MAILBOXES + ' WHERE member_id IN (' + users + ') AND note_id IN (' + notes + '); '
        'DELETE FROM ' + ci.NOTES + ' WHERE note_id IN (' + notes + ') AND sender_id=' + str(CALLER) + ' AND recipient_id=' + str(OTHER) + '; '
        'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + '); '
        'DELETE FROM pawbridge_user.users WHERE (user_id,email,nickname) IN (' + ','.join('(' + str(i) + ',' + literal('f29-' + str(i) + '@fixture.invalid') + ',' + literal(NAMES[i]) + ')' for i in USERS) + '); COMMIT;')


def verify_rest(body, kind, record):
    note = record['note']; receiver = kind != 'sent'
    if kind == 'notifications':
        row = dict(noteId=note['note_id'], kind='PRIVATE_NOTE', actorId=CALLER, actorNickname=NAMES[CALLER],
                   createdAt=note['created_at'], read=False, href='/notes/' + note['note_id'])
        wanted = dict(content=[row], nextCursor=None, unreadCount=1)
    else:
        row = dict(noteId=note['note_id'], body=BODY, direction='INBOX' if receiver else 'SENT',
                   createdAt=note['created_at'], readAt=None if receiver else note['created_at'], favorite=False,
                   counterpartId=CALLER if receiver else OTHER, counterpartNickname=NAMES[CALLER if receiver else OTHER],
                   canReply=receiver, contextHref=None)
        wanted = row if kind == 'detail' else dict(content=[row], totalElements=1, totalPages=1, number=0, size=10)
    actual = copy.deepcopy(body)
    try:
        rows = [actual['data']] if kind == 'detail' else actual['data']['content']
        for item in rows:
            same_instant(item['createdAt'], note['created_at']); item['createdAt'] = note['created_at']
            if kind == 'sent':
                same_instant(item['readAt'], note['created_at']); item['readAt'] = note['created_at']
        numeric = ('unreadCount',) if kind == 'notifications' else () if kind == 'detail' else ('totalElements', 'totalPages', 'number', 'size')
        if type(actual['code']) is not int or any(type(actual['data'][key]) is not int for key in numeric): raise ValueError()
        if actual != dict(code=200, message=SUCCESS, data=wanted): raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise AssertionError('F29 exact owned REST projection differs') from None


def run(ci):
    owner = ci.require_fixture()
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', owner=owner, source_sha=ci.SOURCE, runtime_sha=os.environ.get('GITHUB_SHA'),
                  timezone='Asia/Seoul', protected_database_changes=0, s3_requests=0)
    ci.EVIDENCE.mkdir(parents=True, exist_ok=True)
    receipt = ci.EVIDENCE / 'f29-packaged-result.txt'
    app = stub = owned = None; streams = []; baseline = ci.database_snapshot(); offset = 0
    app_path, stub_path = ci.EVIDENCE / 'f29-packaged-startup.log', ci.EVIDENCE / 'f29-s3-stub.log'
    try:
        ci.require_measurements('jar')
        if ci.COUNTERS != COUNTERS or ci.EXPECTED_JAR_COUNTS != EXPECTED_JAR_COUNTS:
            raise SystemExit('F29 prefix and runtime expected counters differ')
        jar = ci.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
        if not jar.is_file(): raise SystemExit('F29 packaged JAR is missing')
        tokens = load_tokens(ci)
        bucket = prefix = 'f3-' + owner
        base_env, app_env = transport._app_environment(ci, bucket, prefix + '/')
        deadline = time.monotonic() + JOURNEY_BUDGET
        base = endpoint = ''
        def snapshot():
            if time.monotonic() > deadline: raise AssertionError('F29 journey exceeded its bounded budget')
            storage = ci.f3.expect(endpoint, '/__f3__/state', 200, headers={'X-F3-Owner': owner})
            return ci.database_snapshot(), storage['counts'], storage['objects'], storage['requests']
        def returned():
            if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('F29 completed request or open stream retained a transaction')
        def delivery():
            recipient.assert_notes(getattr(recipient, 'acknowledged_records', [])); sender.assert_notes([])
            recipient.assert_resync(0); sender.assert_resync(0)
        def check(path, *, method='POST', status=200, key=None, new=False, replay=False, identity=CALLER,
                  authenticated=True, conflict=False, message=None, security=False, view=None, kind=None):
            before = snapshot()
            headers = {'X-User-Id': str(OTHER if identity == CALLER else CALLER), 'X-User-Role': 'ROLE_ADMIN'}
            if authenticated: headers['Authorization'] = 'Bearer ' + load_tokens(ci)['F29_CALLER' if identity == CALLER else 'F29_OTHER']
            payload = None
            if method == 'POST':
                headers['Content-Type'] = 'application/json'
                payload = b'{' if key is None else json.dumps(dict(recipientId=OTHER, body=RAW_BODY + ('changed' if conflict else ''), requestId=key), ensure_ascii=False).encode()
            started = time.time_ns(); actual, raw, received = ci.f3.request(base, path, method=method, headers=headers, body=payload); finished = time.time_ns()
            after = snapshot()
            if after[1:] != before[1:]: raise AssertionError('F29 request contacted or changed S3')
            if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != (prior.SECURITY_CACHE if security else 'no-store'):
                raise AssertionError('F29 status/cache/cookie differs')
            response = json.loads(raw)
            if new:
                record = send_delta(before[0], after[0], owned, key, started, finished)
                acknowledge(owned, key, response, record, recipient, sender)
                result['recipient_note_checks'] += 1; result['approved_database_write_checks'] += 1
                result['inserted_note_rows'] += 1; result['inserted_mailbox_rows'] += 2; result['inserted_request_rows'] += 1
            else:
                if after[0] != before[0]: raise AssertionError('F29 failed, replay or REST request changed rows')
                if replay:
                    if response != dict(code=200, message=SUCCESS, data={'noteId': owned['ack'][key]}): raise AssertionError('F29 replay returned a different receipt')
                elif view: verify_rest(response, view, committed_records(after[0], owned)[REQUEST_IDS[0]])
                elif response != (dict(code=status, message=message, data=None) if security else {'message': message}):
                    raise AssertionError('F29 exact rejection envelope differs')
                delivery()
            returned(); result['request_checks'] += 1; result['nonstreaming_checks'] += 1
            result['post_checks' if method == 'POST' else 'get_checks'] += 1
            if method == 'POST': result['successful_post_checks' if status == 200 else 'rejected_post_checks'] += 1
            if replay: result['replay_checks'] += 1
            if kind: result[kind + '_checks'] += 1
        def opening(identity):
            before = snapshot(); token = load_tokens(ci)['F29_CALLER' if identity == CALLER else 'F29_OTHER']
            stream = NoteStream(base, {'Authorization': 'Bearer ' + token}); streams.append(stream); stream.wait('ready')
            if snapshot() != before: raise AssertionError('F29 stream opening changed rows or S3')
            returned(); result['request_checks'] += 1; result['get_checks'] += 1
            result['stream_open_checks'] += 1; result['stream_ready_checks'] += 1
            return stream
        with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
            try:
                app, stub, base, endpoint, attempts, offset = ci.f4.start_ready_children(jar, app_env, base_env, owner,
                    bucket, prefix + '/', stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                if ci.database_snapshot() != baseline: raise AssertionError('F29 startup changed business rows')
                state = snapshot()
                if any(state[1].values()) or state[2] or state[3]: raise AssertionError('F29 startup contacted S3')
                owned = seed(ci); sender, recipient = opening(CALLER), opening(OTHER)
                check('/api/v1/notes', key=REQUEST_IDS[0], new=True)
                check('/api/v1/notes', key=REQUEST_IDS[0], replay=True)
                check('/api/v1/notes?box=SENT', method='GET', view='sent', kind='list')
                check('/api/v1/notes', method='GET', identity=OTHER, view='inbox', kind='list')
                check('/api/v1/notes/' + owned['ack'][REQUEST_IDS[0]], method='GET', identity=OTHER, view='detail', kind='detail')
                check('/api/v1/notes/notifications', method='GET', identity=OTHER, view='notifications', kind='notification')
                check('/api/v1/notes', key=REQUEST_IDS[0], conflict=True, status=409, message=CONFLICT, kind='conflict')
                with ci.denied_privilege('note-insert'):
                    check('/api/v1/notes', key=REQUEST_IDS[1], status=503, message=prior.STORAGE, kind='storage_unavailable')
                check('/api/v1/notes', key=REQUEST_IDS[1], new=True, kind='recovery')
                with ci.denied_privilege('nickname'):
                    check('/api/v1/notes', key=REQUEST_IDS[2], status=503, message=prior.MEMBER_UNAVAILABLE, kind='member_unavailable')
                check('/api/v1/notes', key=REQUEST_IDS[2], new=True, kind='recovery')
                check('/api/v1/notes', authenticated=False, status=401, message='인증 토큰이 필요합니다.', security=True, kind='authentication')
                check('/api/v1/notes/extra/extra', status=403, message='접근할 수 없습니다.', security=True, kind='adjacent_denied')
                ci.require_fixture(); result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F29 packaged application'); app = None
                sender.wait_eof(); recipient.wait_eof(); delivery(); result['server_closed_open_streams'] = 2
                shutdown = snapshot()
                if any(shutdown[1].values()) or shutdown[2] or shutdown[3]: raise AssertionError('F29 shutdown contacted or changed S3')
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F29 packaged application')
                finally:
                    try: transport.close_streams(streams)
                    finally:
                        try:
                            if stub is not None: result['stub_sigterm_exit'] = ci.f3.stop_owned_child(stub, 'F29 S3 stub')
                        finally:
                            if owned is not None: cleanup(ci, owned)
        if ci.database_snapshot() != baseline: raise AssertionError('F29 owned cleanup did not restore all eighteen tables')
        ci.require_fixture()
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024: raise AssertionError('F29 child log budget exceeded')
        text = ci.f4.fresh_log(app_path, offset); stub_text = stub_path.read_text()
        if (text.count('Start completed.') != 2 or text.count('Shutdown completed.') != 2 or 'Graceful shutdown complete' not in text
                or text.count('Private note stream resources stopped') != 1 or 'Private note stream resources did not stop within budget' in text):
            raise AssertionError('F29 stream/two-pool normal shutdown evidence is incomplete')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, RAW_BODY, json.dumps(BODY)[1:-1], request_hash(), 'F29 synthetic', *tokens.values(), *NAMES.values(),
                     *('f29-' + str(i) + '@fixture.invalid' for i in USERS))
        if any(v and (v in text or v in stub_text) for v in forbidden): raise AssertionError('F29 child log exposed a private input')
        if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F29 application connections remain after shutdown')
        if {k: result[k] for k in COUNTERS} != EXPECTED_JAR_COUNTS: raise AssertionError('F29 exact send journey counts differ')
        result.update(status='passed', shutdown_pools=2, remaining_application_connections=0,
                      stream_resources_stopped=True, owned_cleanup_restored=True)
    finally:
        receipt.write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F29 send/recipient-note/replay/REST and owned shutdown journey passed')


# Late member-cleanup journey only. The original F29 send run() above is unchanged.
MEMBER_ADMIN = 9973001
MEMBER_IDS = (CALLER, OTHER)
MEMBER_NOTE_A, MEMBER_NOTE_B, MEMBER_ROOM, MEMBER_REQUEST_A, MEMBER_REQUEST_B, MEMBER_CHAT_REQUEST = (
    'f39a0000-0000-4000-8000-' + f'{n:012d}' for n in range(1, 7))
MEMBER_FIRST = '2032-02-29T15:00:00.123456Z'
MEMBER_EXPIRY = '2033-02-28T15:00:00.123456Z'
MEMBER_BODY = 'Member cleanup owned private note'
MEMBER_CHAT_BODY = 'Member cleanup owned private chat'
MEMBER_NAMES = {i: 'Cleanupjar' + str(i) for i in MEMBER_IDS}
MEMBER_COUNTERS = ('request_checks', 'delete_checks', 'successful_delete_checks', 'rejected_delete_checks',
                   'get_checks', 'stream_open_checks', 'stream_ready_checks', 'community_rollback_checks',
                   'user_rollback_checks', 'retry_checks', 'pending_reconnect_checks', 'authentication_checks',
                   'authorization_checks', 'surviving_detail_checks', 'approved_database_write_checks')
# Static journey: SSE2 + admin DELETE5 + pending reconnect1 + surviving detail1.
MEMBER_EXPECTED_COUNTS = dict(zip(MEMBER_COUNTERS, (9, 5, 1, 4, 4, 2, 2, 1, 1, 1, 1, 1, 1, 1, 3)))


def member_load_tokens(ci):
    """Only these three actual-issuer tokens, including the existing DB-absent ADMIN."""
    import base64
    import hmac
    import math
    owner = ci.f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    wanted = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    try:
        mode = path.lstat().st_mode
        if path.is_symlink() or path.resolve() != wanted or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise ValueError()
        values = json.loads(path.read_text())
        secret = os.environ['F3_TEST_JWT_SECRET']
        if len(secret.encode()) < 32: raise ValueError()
        tokens = {}
        decode = lambda s: base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))
        for name, identity, role in (('F19_ADMIN', MEMBER_ADMIN, 'ROLE_ADMIN'),
                                     ('F29_CALLER', CALLER, 'ROLE_USER'), ('F29_OTHER', OTHER, 'ROLE_USER')):
            token = values[name]; parts = token.split('.')
            if len(parts) != 3 or not all(parts): raise ValueError()
            header, claims = json.loads(decode(parts[0])), json.loads(decode(parts[1]))
            digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[header['alg']]
            signature = hmac.new(secret.encode(), (parts[0] + '.' + parts[1]).encode('ascii'), digest).digest()
            if (not hmac.compare_digest(signature, decode(parts[2])) or type(claims['userId']) is not int
                    or claims['userId'] != identity or claims['role'] != role
                    or type(claims['exp']) not in (int, float) or not math.isfinite(claims['exp'])
                    or claims['exp'] < time.time() + 60): raise ValueError()
            tokens[name] = token
    except (OSError, KeyError, TypeError, ValueError, UnicodeError, AttributeError):
        raise SystemExit('Member cleanup requires three fresh actual-issuer tokens in the owned regular 0600 file') from None
    return tokens


def member_seed_rows():
    """Known private inputs/full source columns, independent of a runtime response."""
    rows = {k: [] for k in ('users', 'favorites', 'refresh_tokens', 'shelter_applications', 'private_note_members',
                            'private_notes', 'private_note_mailboxes', 'private_note_requests', 'private_note_blocks',
                            'chat_rooms', 'chat_members', 'chat_messages', 'chat_requests')}
    for identity in MEMBER_IDS:
        rows['users'].append(dict(user_id=identity, care_reg_no=None, created_at=None,
            email='cleanupjar-' + str(identity) + '@fixture.invalid', name='Cleanup synthetic',
            nickname=MEMBER_NAMES[identity], password=None, provider='GOOGLE', provider_id=None,
            role='ROLE_USER', updated_at=None))
        rows['private_note_members'].append(dict(member_id=identity))
    plain = MEMBER_FIRST[:-1]
    rows['favorites'] = [dict(favorite_id=CALLER, animal_id=1, created_at=plain, user_id=CALLER)]
    rows['refresh_tokens'] = [dict(refresh_token_id=CALLER, created_at=plain, expires_at=MEMBER_EXPIRY[:-1],
                                   token='cleanupjar-refresh-' + str(CALLER), user_id=CALLER)]
    rows['shelter_applications'] = [dict(id=CALLER, user_id=CALLER, shelter_name='Synthetic cleanup', status='PENDING',
        requested_at=plain, reviewed_by=None, reviewed_at=None, review_note=None, care_reg_no=None)]
    for identity, sender, recipient in ((MEMBER_NOTE_A, CALLER, OTHER), (MEMBER_NOTE_B, OTHER, CALLER)):
        rows['private_notes'].append(dict(note_id=identity, sender_id=sender, recipient_id=recipient,
            body=MEMBER_BODY, reply_to=None, context_type='POST', context_id=123,
            created_at=MEMBER_FIRST, expires_at=MEMBER_EXPIRY))
    for member, identity, direction in ((CALLER, MEMBER_NOTE_A, 'SENT'), (OTHER, MEMBER_NOTE_A, 'INBOX'),
                                         (CALLER, MEMBER_NOTE_B, 'INBOX')):
        rows['private_note_mailboxes'].append(dict(member_id=member, note_id=identity, direction=direction,
            read_at=MEMBER_FIRST if direction == 'SENT' else None, favorite=False, created_at=MEMBER_FIRST))
    for member, request in ((OTHER, MEMBER_REQUEST_A), (CALLER, MEMBER_REQUEST_B)):
        rows['private_note_requests'].append(dict(sender_id=member, request_id=request, request_hash='a' * 64,
                                                note_id=MEMBER_NOTE_A, created_at=MEMBER_FIRST))
    rows['private_note_blocks'] = [dict(member_id=CALLER, blocked_id=OTHER, created_at=MEMBER_FIRST)]
    rows['chat_rooms'] = [dict(room_id=MEMBER_ROOM, first_member_id=CALLER, second_member_id=OTHER, latest_sequence=1)]
    rows['chat_members'] = [dict(room_id=MEMBER_ROOM, member_id=i, read_through=0, hidden_through=-1) for i in MEMBER_IDS]
    rows['chat_messages'] = [dict(room_id=MEMBER_ROOM, sequence=1, sender_id=CALLER, body=MEMBER_CHAT_BODY,
        context_type=None, context_id=None, created_at=MEMBER_FIRST, expires_at=MEMBER_EXPIRY)]
    rows['chat_requests'] = [dict(sender_id=CALLER, request_id=MEMBER_CHAT_REQUEST, request_hash='b' * 64,
                                 room_id=MEMBER_ROOM, sequence=1, created_at=MEMBER_FIRST)]
    return rows


def member_ordered(ci, state):
    result = copy.deepcopy(state)
    for name, _, order in ci.SNAPSHOT_TABLES + ci.MEMBER_TABLES:
        result[name].sort(key=lambda row: tuple(row[k] for k in order.split(',')))
    return result


def member_expected(ci, owned, phase):
    expected = copy.deepcopy(owned['baseline'])
    for name, rows in owned['seed_rows'].items(): expected[name].extend(copy.deepcopy(rows))
    if phase != 'seed': expected['contact_deletions'].append(copy.deepcopy(owned['pending']))
    if phase in ('community', 'final'):
        expected['private_note_members'] = [r for r in expected['private_note_members'] if r['member_id'] != CALLER]
        expected['private_note_mailboxes'] = [r for r in expected['private_note_mailboxes'] if r['member_id'] != CALLER]
        expected['private_note_requests'] = [r for r in expected['private_note_requests'] if r['sender_id'] != CALLER]
        expected['private_note_blocks'] = [r for r in expected['private_note_blocks'] if r['member_id'] != CALLER and r['blocked_id'] != CALLER]
        for r in expected['private_note_requests']:
            if r['note_id'] in (MEMBER_NOTE_A, MEMBER_NOTE_B): r['request_hash'] = None
        expected['private_notes'] = [r for r in expected['private_notes'] if r['note_id'] != MEMBER_NOTE_B]
        for r in expected['private_notes']:
            if r['note_id'] == MEMBER_NOTE_A: r.update(sender_id=None, context_type=None, context_id=None)
        expected['chat_members'] = [r for r in expected['chat_members'] if r['member_id'] != CALLER]
        expected['chat_requests'] = [r for r in expected['chat_requests'] if r['sender_id'] != CALLER]
        for r in expected['chat_messages']:
            if r['room_id'] == MEMBER_ROOM: r['sender_id'] = None
        for r in expected['chat_rooms']:
            if r['room_id'] == MEMBER_ROOM: r['first_member_id'] = None
    if phase == 'final':
        for name in ('users', 'favorites', 'refresh_tokens', 'shelter_applications', 'contact_deletions'):
            expected[name] = [r for r in expected[name] if r['user_id'] != CALLER]
    return member_ordered(ci, expected)


def member_assert_state(ci, owned, state, phase):
    if state != member_expected(ci, owned, phase):
        raise AssertionError('Member cleanup changed a protected row, column or unexpected reference')


def member_reserved_free(state):
    uuids = {MEMBER_NOTE_A, MEMBER_NOTE_B, MEMBER_ROOM, MEMBER_REQUEST_A, MEMBER_REQUEST_B, MEMBER_CHAT_REQUEST}
    # Reserve all relevant identifiers before any insert; never infer ownership from NULL participants later.
    fields = {'users': ('user_id',), 'favorites': ('favorite_id', 'user_id'), 'refresh_tokens': ('refresh_token_id', 'user_id'),
        'shelter_applications': ('id', 'user_id', 'reviewed_by'), 'contact_deletions': ('user_id',),
        'private_note_members': ('member_id',), 'private_note_blocks': ('member_id', 'blocked_id'),
        'private_notes': ('note_id', 'sender_id', 'recipient_id', 'reply_to'),
        'private_note_mailboxes': ('member_id', 'note_id'), 'private_note_requests': ('sender_id', 'request_id', 'note_id'),
        'chat_rooms': ('room_id', 'first_member_id', 'second_member_id'), 'chat_members': ('room_id', 'member_id'),
        'chat_messages': ('room_id', 'sender_id'), 'chat_requests': ('sender_id', 'room_id', 'request_id')}
    for table, columns in fields.items():
        if any(row.get(k) in (*MEMBER_IDS, *uuids) for row in state[table] for k in columns):
            raise SystemExit('Member cleanup refuses occupied or externally referenced seed identifiers')
    if any(r['user_id'] == MEMBER_ADMIN for r in state['users']):
        raise SystemExit('Member cleanup requires its ADMIN principal to remain absent from the database')


def member_seed(ci, owned):
    ci.member_require_fixture()
    if ci.member_database_snapshot() != owned['baseline']: raise SystemExit('Member cleanup baseline changed before seed')
    member_reserved_free(owned['baseline'])
    relations = {k: relation for k, relation, _ in ci.SNAPSHOT_TABLES + ci.MEMBER_TABLES}
    sql = ['BEGIN;']
    for name, rows in owned['seed_rows'].items():
        for row in rows:
            def value(v):
                return 'NULL' if v is None else str(v).lower() if type(v) is bool else str(v) if type(v) is int else literal(v)
            sql.append('INSERT INTO ' + relations[name] + '(' + ','.join(row) + ') VALUES('
                       + ','.join(value(v) for v in row.values()) + ');')
    sql.append('COMMIT;')
    owned['reserved'] = True  # Only the already-empty exact namespace; lost COMMIT ACK is re-observed below.
    ci.owned_sql(input_text='\n'.join(sql))
    if not member_observe_seed(ci, owned):
        raise AssertionError('Member cleanup seed acknowledgement contains no inserted rows')


def member_observe_seed(ci, owned):
    state = ci.member_database_snapshot()
    if state == owned['baseline']: return False
    # PostgreSQL renders equivalent TIMESTAMPTZ offsets. Accept representation only on known newly inserted cells.
    for table, rows in owned['seed_rows'].items():
        keys = next(order.split(',') for key, _, order in ci.SNAPSHOT_TABLES + ci.MEMBER_TABLES if key == table)
        for row in rows:
            matches = [r for r in state[table] if all(r[k] == row[k] for k in keys)]
            if len(matches) != 1: raise AssertionError('Member cleanup seed acknowledgement is incomplete')
            actual = matches[0]
            for key, value in list(row.items()):
                if isinstance(value, str) and value.endswith('Z') and key in ('created_at', 'expires_at', 'read_at'):
                    if instant_ns(actual[key]) != instant_ns(value): raise AssertionError('Member cleanup seed time differs')
                    row[key] = actual[key]
    member_assert_state(ci, owned, state, 'seed')
    owned['ack'] = True
    return True


def member_observe_pending(owned, state, started, finished):
    rows = [r for r in state['contact_deletions'] if r['user_id'] == CALLER]
    if len(rows) != 1 or set(rows[0]) != {'user_id', 'started_at'}:
        raise AssertionError('Member cleanup pending commit is missing or has unexpected columns')
    value = rows[0]
    if owned.get('pending') is None:
        if not started - 1_000_000_000 <= instant_ns(value['started_at']) <= finished + 1_000_000_000:
            raise AssertionError('Member cleanup pending time is outside the observed request')
        owned['pending'] = copy.deepcopy(value)
    elif value != owned['pending']:
        raise AssertionError('Member cleanup retry replaced the original pending marker')


def member_cleanup_owned(ci, owned):
    if not owned.get('reserved'): return
    ci.member_require_identity()
    state = ci.member_database_snapshot()
    if state == owned['baseline']: return
    if not owned.get('ack'):
        member_observe_seed(ci, owned)  # Only an atomic, exact seed can survive a lost seed COMMIT acknowledgement.
        state = ci.member_database_snapshot()
    pending = [r for r in state['contact_deletions'] if r['user_id'] == CALLER]
    if pending and owned.get('pending') is None:
        member_observe_pending(owned, state, owned['started_ns'], time.time_ns())
    phases = ['seed'] + (['pending', 'community', 'final'] if owned.get('pending') is not None else [])
    if not any(state == member_expected(ci, owned, p) for p in phases):
        raise SystemExit('Member cleanup refuses changed, unknown or externally referenced owned rows')
    users = ','.join(map(str, MEMBER_IDS)); notes = ','.join(literal(n) + '::uuid' for n in (MEMBER_NOTE_A, MEMBER_NOTE_B))
    requests = ','.join(literal(n) + '::uuid' for n in (MEMBER_REQUEST_A, MEMBER_REQUEST_B))
    # Exact reserved keys, FK order, including identifiers retained after source SET NULL effects.
    sql = ('BEGIN; DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=' + str(CALLER)
        + ' AND request_id=' + literal(MEMBER_CHAT_REQUEST) + '::uuid; DELETE FROM pawbridge_community.member_chat_room_members WHERE room_id='
        + literal(MEMBER_ROOM) + '::uuid AND member_id IN (' + users + '); DELETE FROM pawbridge_community.member_chat_messages WHERE room_id='
        + literal(MEMBER_ROOM) + '::uuid AND sequence=1; DELETE FROM pawbridge_community.member_chat_rooms WHERE room_id=' + literal(MEMBER_ROOM)
        + '::uuid; DELETE FROM ' + ci.REQUESTS + ' WHERE sender_id IN (' + users + ') AND request_id IN (' + requests + '); DELETE FROM '
        + ci.MAILBOXES + ' WHERE member_id IN (' + users + ') AND note_id IN (' + notes + '); DELETE FROM ' + ci.NOTES + ' WHERE note_id IN ('
        + notes + '); DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=' + str(CALLER) + ' AND blocked_id=' + str(OTHER)
        + '; DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + '); DELETE FROM pawbridge_user.contact_deletions WHERE user_id='
        + str(CALLER) + '; DELETE FROM pawbridge_user.favorites WHERE favorite_id=' + str(CALLER) + ' AND user_id=' + str(CALLER)
        + '; DELETE FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=' + str(CALLER) + ' AND user_id=' + str(CALLER)
        + '; DELETE FROM pawbridge_user.shelter_applications WHERE id=' + str(CALLER) + ' AND user_id=' + str(CALLER)
        + '; DELETE FROM pawbridge_user.users WHERE (user_id,email,nickname) IN ('
        + ','.join('(' + str(i) + ',' + literal('cleanupjar-' + str(i) + '@fixture.invalid') + ',' + literal(MEMBER_NAMES[i]) + ')' for i in MEMBER_IDS)
        + '); COMMIT;')
    ci.member_require_identity(); ci.owned_sql(input_text=sql)
    if ci.member_database_snapshot() != owned['baseline']: raise AssertionError('Member cleanup did not restore its full twenty-four-table baseline')


def member_verify_detail(value):
    wanted = dict(noteId=MEMBER_NOTE_A, body=MEMBER_BODY, direction='INBOX', createdAt=MEMBER_FIRST,
        readAt=None, favorite=False, counterpartId=None, counterpartNickname='탈퇴한 회원', canReply=False, contextHref=None)
    try:
        actual = copy.deepcopy(value)
        if instant_ns(actual['data']['createdAt'], wire=True) != instant_ns(MEMBER_FIRST): raise ValueError()
        actual['data']['createdAt'] = MEMBER_FIRST
        if type(actual['code']) is not int or actual != dict(code=200, message=SUCCESS, data=wanted): raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise AssertionError('Member cleanup surviving note body or withdrawn projection differs') from None



def member_private_log_inputs(tokens):
    # Exact private inputs only; no redaction or untrusted exception text is published.
    return (os.environ.get('F2_TEST_DB_PASSWORD', ''), os.environ.get('F3_TEST_WRITER_PASSWORD', ''),
        os.environ.get('F3_TEST_JWT_SECRET', ''), MEMBER_BODY, MEMBER_CHAT_BODY, 'Cleanup synthetic',
        'Synthetic cleanup', 'cleanupjar-refresh-' + str(CALLER), 'a' * 64, 'b' * 64,
        *tokens, *MEMBER_NAMES.values(), *(str(i) for i in (*MEMBER_IDS, MEMBER_ADMIN)),
        MEMBER_NOTE_A, MEMBER_NOTE_B, MEMBER_ROOM, MEMBER_REQUEST_A, MEMBER_REQUEST_B, MEMBER_CHAT_REQUEST,
        *('cleanupjar-' + str(i) + '@fixture.invalid' for i in MEMBER_IDS))


def member_publish_safe_logs(ci, raw_paths, tokens, stopped, failed):
    """Raw child FDs always stay outside artifacts, including failed/unknown termination.

    Only bounded, quiescent, exact-input-free bytes can become artifact logs. This is
    a late-journey ownership rule, not a redaction/quarantine framework.
    """
    if not raw_paths: return 'not-created'
    quiescent = all(stopped.values())
    issue = 'unconfirmed-stop' if not quiescent else None
    copies = []
    for raw, filename in zip(raw_paths, ('member-cleanup-packaged-startup.log', 'member-cleanup-s3-stub.log')):
        try:
            mode = raw.lstat().st_mode
            if raw.is_symlink() or raw.resolve() != raw or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
                raise ValueError()
            with raw.open('rb') as source: data = source.read(1024 * 1024 + 1)
            if len(data) > 1024 * 1024: raise ValueError()
            text = data.decode('utf-8')
            if any(value and value in text for value in member_private_log_inputs(tokens)):
                issue = 'private-input-refused'
                continue
            copies.append((filename, data))
        except (OSError, UnicodeError, ValueError):
            issue = 'inspection-or-publication-failed'
    if issue: return issue
    if failed: return 'failure-private-only'  # Never publish unknown failure log content, even after a clean stop.
    try:
        for filename, data in copies:
            # No child was ever given this FD; a still-writing child cannot append to this artifact.
            with (ci.EVIDENCE / filename).open('xb') as output: output.write(data)
    except OSError:
        return 'inspection-or-publication-failed'
    return 'safe-copied'


def run_member_cleanup(ci):
    owner = ci.member_require_fixture()
    result = dict.fromkeys(MEMBER_COUNTERS, 0)
    result.update(status='incomplete', owner=owner, source_sha=ci.SOURCE, runtime_sha=os.environ.get('GITHUB_SHA'),
        timezone='Asia/Seoul', chat_mode=False, protected_database_changes=0, s3_requests=0)
    ci.EVIDENCE.mkdir(parents=True, exist_ok=True)
    receipt = ci.EVIDENCE / 'member-cleanup-packaged-result.txt'
    app = stub = None; streams = []; tokens = {}; used_tokens = set(); owned = None; offset = 0
    raw_paths = (); stopped = {'app': False, 'stub': False}
    baseline = ci.member_database_snapshot()
    try:
        ci.member_require_runtime_results()  # Exact tag PG12 including last real-issuer refresh; not ordinary F29 results.
        if not re.fullmatch('[0-9a-f]{40}', result['runtime_sha'] or ''):
            raise SystemExit('Member cleanup packaged source commit is missing')
        jar = ci.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
        if not jar.is_file(): raise SystemExit('Member cleanup packaged JAR is missing')
        tokens = member_load_tokens(ci)  # Initial private log mask only; every authenticated use reloads after snapshot.
        used_tokens.update(tokens.values())
        temporary = Path(os.environ['RUNNER_TEMP']).resolve()
        workspace = ci.ROOT.resolve().parent
        if not temporary.is_dir() or workspace == temporary or workspace in temporary.parents:
            raise SystemExit('Member cleanup raw log directory must be outside the artifact workspace')
        raw_directory = temporary / ('member-cleanup-' + owner)
        try:
            raw_directory.mkdir(mode=0o700)  # Refuse reuse/symlinks; only this owner-attempt creates it.
        except OSError:
            raise SystemExit('Member cleanup private log directory is unavailable') from None
        raw_paths = (raw_directory / 'application.log', raw_directory / 'storage.log')
        app_path, stub_path = raw_paths
        bucket = prefix = 'f3-' + owner
        base_env, app_env = transport._app_environment(ci, bucket, prefix + '/')
        app_env.update(MEMBER_CHAT_ENABLED='false', PAWBRIDGE_CHAT_ENABLED='false')
        deadline = time.monotonic() + JOURNEY_BUDGET
        base = endpoint = ''
        def snapshot():
            if time.monotonic() > deadline: raise AssertionError('Member cleanup journey exceeded its bounded budget')
            storage = ci.f3.expect(endpoint, '/__f3__/state', 200, headers={'X-F3-Owner': owner})
            return ci.member_database_snapshot(), storage['counts'], storage['objects'], storage['requests']
        def returned():
            if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('Member cleanup completed request or stream retained a transaction')
        def stream_counts(own, other, own_eof=False):
            victim.assert_resync(own); survivor.assert_resync(other)
            if own_eof: victim.wait_eof()
            else:
                with victim.condition:
                    if victim.eof or victim.failed: raise AssertionError('Member cleanup rollback prematurely closed the victim stream')
        def check(path, *, method='DELETE', token='F19_ADMIN', status=200, phase=None,
                  message=None, security=False, detail=False, spoof=False, kind=None):
            before = snapshot()
            headers = {'Accept': '*/*', 'X-User-Id': str(MEMBER_ADMIN if spoof else OTHER), 'X-User-Role': 'ROLE_ADMIN'}
            if token is not None:
                current = member_load_tokens(ci); used_tokens.update(current.values())
                headers['Authorization'] = 'Bearer ' + current[token]
            # The token freshness check is the last external-input operation before HTTP, after both snapshots.
            started = time.time_ns()
            actual, raw, received = ci.f3.request(base, path, method=method, headers=headers)
            finished = time.time_ns(); after = snapshot()
            if after[1:] != before[1:]: raise AssertionError('Member cleanup request contacted or changed S3')
            if phase:
                if phase != 'final': member_observe_pending(owned, after[0], started, finished)
                member_assert_state(ci, owned, after[0], phase)
                result['approved_database_write_checks'] += 1
            elif after[0] != before[0]: raise AssertionError('Member cleanup read/auth request changed protected rows')
            if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != (prior.SECURITY_CACHE if security else 'no-store'):
                raise AssertionError('Member cleanup HTTP status/cache/cookie differs')
            try: value = json.loads(raw)
            except (ValueError, TypeError): raise AssertionError('Member cleanup HTTP response is not the expected JSON') from None
            if detail: member_verify_detail(value)
            else:
                expected = dict(code=status, message=message, data=None) if security else {'message': message}
                if value != expected: raise AssertionError('Member cleanup exact response envelope differs')
            returned(); result['request_checks'] += 1; result['delete_checks' if method == 'DELETE' else 'get_checks'] += 1
            if method == 'DELETE': result['successful_delete_checks' if status == 200 else 'rejected_delete_checks'] += 1
            if kind: result[kind + '_checks'] += 1
        def opening(name):
            before = snapshot()
            current = member_load_tokens(ci); used_tokens.update(current.values())
            headers = {'Authorization': 'Bearer ' + current[name]}
            stream = transport.Stream(base, headers); streams.append(stream); stream.wait('ready')
            if snapshot() != before: raise AssertionError('Member cleanup stream opening changed rows or S3')
            returned(); result['request_checks'] += 1; result['get_checks'] += 1
            result['stream_open_checks'] += 1; result['stream_ready_checks'] += 1
            return stream
        with os.fdopen(os.open(app_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as app_log, \
             os.fdopen(os.open(stub_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stub_log:
            try:
                app, stub, base, endpoint, attempts, offset = ci.f4.start_ready_children(jar, app_env, base_env, owner,
                    bucket, prefix + '/', stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                start = snapshot()
                if start[0] != baseline or any(start[1].values()) or start[2] or start[3]:
                    raise AssertionError('Member cleanup startup changed protected rows or contacted S3')
                owned = dict(baseline=copy.deepcopy(baseline), seed_rows=member_seed_rows(), reserved=False,
                             ack=False, pending=None, started_ns=time.time_ns())
                member_seed(ci, owned)
                victim, survivor = opening('F29_CALLER'), opening('F29_OTHER')
                path = '/api/v1/admin/users/' + str(CALLER)
                check(path, token=None, status=401, message='인증 토큰이 필요합니다.', security=True, kind='authentication')
                check(path, token='F29_OTHER', status=403, message='관리자 권한이 필요합니다.', security=True, spoof=True, kind='authorization')
                stream_counts(0, 0)
                with ci.member_denied_privilege('chat-update'):
                    check(path, status=503, phase='pending', security=True,
                          message='쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.', kind='community_rollback')
                    stream_counts(0, 0)  # Heartbeat checks expiry/closing only, never User pending/active.
                with ci.member_denied_privilege('user-delete'):
                    check(path, status=500, phase='community', message='서버 에러!', security=True, kind='user_rollback')
                    victim.wait_eof(); survivor.wait('resync', 1); stream_counts(0, 1, own_eof=True)
                # Still-pending User row: verified normal JWT is valid, current-member policy rejects INACTIVE403.
                check('/api/v1/notes/stream', method='GET', token='F29_CALLER', status=403,
                      message='이용할 수 없는 회원입니다.', kind='pending_reconnect')
                stream_counts(0, 1, own_eof=True)
                check(path, phase='final', message='회원이 삭제되었습니다.', security=True, kind='retry')
                survivor.wait('resync', 2); stream_counts(0, 2, own_eof=True)
                check('/api/v1/notes/' + MEMBER_NOTE_A, method='GET', token='F29_OTHER', detail=True, kind='surviving_detail')
                stream_counts(0, 2, own_eof=True)
                ci.member_require_fixture()
                result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'Member cleanup packaged application'); stopped['app'] = True; app = None
                survivor.wait_eof(); stream_counts(0, 2, own_eof=True)
                result.update(member_commit_closed_streams=1, server_closed_open_streams=1, survivor_resync_checks=2)
                end = snapshot()
                if any(end[1].values()) or end[2] or end[3]: raise AssertionError('Member cleanup shutdown contacted S3')
            finally:
                try:
                    if app is not None:
                        result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'Member cleanup packaged application'); stopped['app'] = True
                finally:
                    try: transport.close_streams(streams)
                    finally:
                        try:
                            if stub is not None:
                                result['stub_sigterm_exit'] = ci.f3.stop_owned_child(stub, 'Member cleanup S3 stub'); stopped['stub'] = True
                        finally:
                            if owned is not None: member_cleanup_owned(ci, owned)
        if ci.member_database_snapshot() != baseline: raise AssertionError('Member cleanup did not restore its full twenty-four-table baseline')
        ci.member_require_fixture()
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('Member cleanup child log budget exceeded')
        text = ci.f4.fresh_log(app_path, offset); stub_text = stub_path.read_text()
        if (text.count('Start completed.') != 2 or text.count('Shutdown completed.') != 2 or 'Graceful shutdown complete' not in text
                or text.count('Private note stream resources stopped') != 1 or 'Private note stream resources did not stop within budget' in text):
            raise AssertionError('Member cleanup stream/two-pool shutdown evidence is incomplete')
        if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('Member cleanup application connections remain after shutdown')
        if {k: result[k] for k in MEMBER_COUNTERS} != MEMBER_EXPECTED_COUNTS:
            raise AssertionError('Member cleanup exact late journey counts differ')
        result.update(status='passed', owned_cleanup_restored=True, stream_resources_stopped=True,
                      shutdown_pools=2, remaining_application_connections=0)
    finally:
        import sys
        failed = sys.exc_info()[0] is not None
        log_status = member_publish_safe_logs(ci, raw_paths, used_tokens, stopped, failed)
        result['child_log_evidence'] = log_status
        refused = log_status not in ('not-created', 'safe-copied', 'failure-private-only')
        if failed or refused: result['status'] = 'incomplete'
        receipt.write_text(json.dumps(result, indent=2) + '\n')
        if refused and not failed:
            raise AssertionError('Member cleanup private or unconfirmed child logs were not published') from None
    print('Hosted false-chat member cleanup/pending/retry and owned resource journey passed')
