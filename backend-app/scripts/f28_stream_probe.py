#!/usr/bin/env python3
"""Hosted F28 deletion/FK/resync probe. Import is inert; counters are a contract.

Only the bounded generic transport is inherited. Controlled expected snapshots
below are not an independent original SQL oracle or actual runtime evidence.
"""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import stat
import time

from f26_stream_probe import Stream, close_streams

CALLER, OTHER = 9982001, 9982002
USERS = (CALLER, OTHER)
A, B, C = tuple('f2800000-0000-4000-8000-' + f'{n:012d}' for n in (1, 2, 3))
NOTE_IDS = (A, B, C)
REQUEST_ID = 'f2800000-0000-4000-8000-000000000010'
REQUEST_KEY = (CALLER, REQUEST_ID)
BOX_KEYS = ((CALLER, A), (OTHER, A), (CALLER, B), (CALLER, C))
NAMES = {CALLER: 'F28caller9982001', OTHER: 'F28other9982002'}
BODY = 'F28 owned synthetic note'
REQUEST_HASH = 'f' * 64
SUCCESS = '요청이 성공적으로 처리되었습니다.'
STORAGE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MISSING = '쪽지를 찾을 수 없습니다.'
SECURITY_CACHE = 'no-cache, no-store, max-age=0, must-revalidate'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'delete_checks', 'successful_delete_checks', 'rejected_delete_checks',
            'get_checks', 'notification_checks', 'detail_checks', 'list_checks', 'stream_open_checks',
            'stream_ready_checks', 'resync_checks', 'other_resync_checks', 'storage_unavailable_checks',
            'member_unavailable_checks', 'recovery_checks', 'repeated_delete_checks', 'not_found_checks',
            'authentication_checks', 'adjacent_denied_checks', 'approved_database_write_checks',
            'deleted_mailbox_rows', 'deleted_note_rows', 'cleared_request_links', 'cleared_reply_links')
# HTTP18 = DELETE9 (success3/reject6) + GET9 (open SSE2/finite7).
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (18, 16, 9, 3, 6, 9, 3, 3, 1, 2, 2, 3, 0, 2, 1, 3, 1, 3, 1, 1, 3, 3, 2, 1, 1)))
JOURNEY_BUDGET = 180


def _claims(token, identity, secret):
    def decode(value):
        return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
    try:
        parts = token.split('.')
        if len(parts) != 3 or not all(parts):
            raise ValueError()
        header, claims = json.loads(decode(parts[0])), json.loads(decode(parts[1]))
        digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[header['alg']]
        signature = hmac.new(secret.encode('utf-8'), (parts[0] + '.' + parts[1]).encode('ascii'), digest).digest()
        valid = (hmac.compare_digest(signature, decode(parts[2])) and type(claims['userId']) is int
                 and claims['userId'] == identity and claims['role'] == 'ROLE_USER'
                 and type(claims['exp']) in (int, float) and math.isfinite(claims['exp'])
                 and claims['exp'] >= time.time() + 60)
    except (AttributeError, KeyError, TypeError, ValueError, UnicodeError):
        raise SystemExit('F28 normal issuer token identity/signature/freshness is invalid') from None
    if not valid:
        raise SystemExit('F28 normal issuer token identity/signature/freshness differs')


def load_tokens(ci):
    owner = ci.f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    try:
        mode = path.lstat().st_mode
        if path.is_symlink() or path.resolve() != expected or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise ValueError()
        values = json.loads(path.read_text())
        tokens = {name: values[name] for name in ('F28_CALLER', 'F28_OTHER')}
        secret = os.environ['F3_TEST_JWT_SECRET']
        if len(secret.encode('utf-8')) < 32:
            raise ValueError()
        for name, identity in (('F28_CALLER', CALLER), ('F28_OTHER', OTHER)):
            _claims(tokens[name], identity, secret)
    except (OSError, KeyError, TypeError, ValueError):
        raise SystemExit('F28 token input is not the owned regular 0600 issuer file') from None
    return tokens


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def instant(value, wire=False):
    ending = 'Z' if wire else r'(?:Z|[+-]\d{2}:\d{2})'
    if type(value) is not str or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?' + ending, value):
        raise AssertionError('F28 projection timestamp must be a microsecond ISO Instant')
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)


def seed(ci):
    ci.require_fixture()
    users = ','.join(map(str, USERS))
    notes = ','.join(literal(n) + '::uuid' for n in NOTE_IDS)
    if ci.owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + ')) + '
            '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE note_id IN (' + notes + ') OR sender_id IN (' + users
            + ') OR recipient_id IN (' + users + ') OR reply_to IN (' + notes + ')) + '
            '(SELECT count(*) FROM ' + ci.MAILBOXES + ' WHERE member_id IN (' + users + ') OR note_id IN (' + notes + ')) + '
            '(SELECT count(*) FROM ' + ci.REQUESTS + ' WHERE sender_id IN (' + users + ') OR note_id IN (' + notes
            + ') OR request_id=' + literal(REQUEST_ID) + '::uuid) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
            + ') OR blocked_id IN (' + users + '))') != '0':
        raise SystemExit('F28 refuses nonempty reserved User/member/note/request/reference identities')
    now = datetime.now(timezone.utc)
    created = (now - timedelta(minutes=1)).replace(microsecond=123456).isoformat().replace('+00:00', 'Z')
    read_at = (now - timedelta(seconds=30)).replace(microsecond=654321).isoformat().replace('+00:00', 'Z')
    expires = (now + timedelta(hours=1)).isoformat().replace('+00:00', 'Z')
    user_rows = ','.join('(' + str(i) + ',' + literal(NAMES[i]) + ',' + literal('f28-' + str(i) + '@fixture.invalid')
                         + ",'F28 synthetic','GOOGLE','ROLE_USER')" for i in USERS)
    note_rows = ','.join('(' + literal(n) + '::uuid,' + str(CALLER if n == B else OTHER) + ','
                         + str(OTHER if n == B else CALLER) + ',' + literal(BODY) + ','
                         + (literal(B) + '::uuid' if n == C else 'NULL') + ',' + literal(created) + '::timestamptz,'
                         + literal(expires) + '::timestamptz)' for n in NOTE_IDS)
    boxes = ','.join('(' + str(member) + ',' + literal(n) + '::uuid,'
            + literal('SENT' if member == OTHER or n == B else 'INBOX') + ','
            + (literal(read_at) + '::timestamptz' if member == OTHER or n == C else 'NULL') + ','
            + ('true' if member == CALLER and n in (A, C) else 'false') + ',' + literal(created) + '::timestamptz)'
            for member, n in BOX_KEYS)
    ci.owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + user_rows + '; '
        'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(' + str(CALLER) + '),(' + str(OTHER) + '); '
        'INSERT INTO ' + ci.NOTES + '(note_id,sender_id,recipient_id,body,reply_to,created_at,expires_at) VALUES' + note_rows + '; '
        'INSERT INTO ' + ci.MAILBOXES + '(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + boxes + '; '
        'INSERT INTO ' + ci.REQUESTS + '(sender_id,request_id,request_hash,note_id,created_at) VALUES('
        + str(CALLER) + ',' + literal(REQUEST_ID) + '::uuid,' + literal(REQUEST_HASH) + ',' + literal(B) + '::uuid,'
        + literal(created) + '::timestamptz); COMMIT;')
    return dict(users=USERS, notes=NOTE_IDS, request=REQUEST_KEY, created=created, read_at=read_at, expires=expires)


def deletion_delta(before, after, note):
    if note not in NOTE_IDS:
        raise ValueError('F28 permits deletion of only an acknowledged caller mailbox')
    expected = copy.deepcopy(before)
    boxes = expected['private_note_mailboxes']
    own = [r for r in boxes if r['member_id'] == CALLER and r['note_id'] == note]
    notes = expected['private_notes']
    originals = [r for r in notes if r['note_id'] == note]
    if len(own) != 1 or len(originals) != 1:
        raise AssertionError('F28 deletion requires one current owned mailbox and original note')
    boxes.remove(own[0])
    counts = dict(deleted_mailbox_rows=1, deleted_note_rows=0, cleared_request_links=0, cleared_reply_links=0)
    if not any(r['note_id'] == note for r in boxes):
        notes.remove(originals[0]); counts['deleted_note_rows'] = 1
        for row in expected['private_note_requests']:
            if row['note_id'] == note:
                if (row['sender_id'], row['request_id']) != REQUEST_KEY:
                    raise AssertionError('F28 refuses an unowned request FK delta')
                row['note_id'] = None; counts['cleared_request_links'] += 1
        for row in notes:
            if row['reply_to'] == note:
                if row['note_id'] != C or note != B:
                    raise AssertionError('F28 refuses an unowned reply FK delta')
                row['reply_to'] = None; counts['cleared_reply_links'] += 1
    wanted = (1, 0, 0, 0) if note == A else (1, 1, 1, 1) if note == B else (1, 1, 0, 0)
    if tuple(counts.values()) != wanted or after != expected:
        raise AssertionError('F28 changed more than its exact owned mailbox/orphan/FK transition')
    return counts


def cleanup(ci, owned):
    if owned.get('users') != USERS or owned.get('notes') != NOTE_IDS or owned.get('request') != REQUEST_KEY:
        raise ValueError('F28 cleanup requires the acknowledged exact atomic seed')
    ci.require_owner()
    state = ci.database_snapshot()
    users = ','.join(map(str, USERS)); notes = ','.join(literal(n) + '::uuid' for n in NOTE_IDS)
    rows = [r for r in state['private_notes'] if r['note_id'] in NOTE_IDS]
    boxes = [r for r in state['private_note_mailboxes'] if r['note_id'] in NOTE_IDS or r['member_id'] in USERS]
    requests = [r for r in state['private_note_requests'] if r['sender_id'] in USERS
                or r['note_id'] in NOTE_IDS or r['request_id'] == REQUEST_ID]
    if (A not in {r['note_id'] for r in rows}
            or any(r['body'] != BODY or r['sender_id'] != (CALLER if r['note_id'] == B else OTHER)
                   or r['recipient_id'] != (OTHER if r['note_id'] == B else CALLER)
                   or r['reply_to'] not in ((B, None) if r['note_id'] == C else (None,)) for r in rows)
            or len(boxes) != len({(r['member_id'], r['note_id']) for r in boxes})
            or any((r['member_id'], r['note_id']) not in BOX_KEYS for r in boxes)
            or (OTHER, A) not in {(r['member_id'], r['note_id']) for r in boxes}
            or len(requests) != 1 or (requests[0]['sender_id'], requests[0]['request_id']) != REQUEST_KEY
            or requests[0]['request_hash'] != REQUEST_HASH or requests[0]['note_id'] not in (B, None)):
        raise SystemExit('F28 cleanup refuses altered or unowned note/mailbox/request identities')
    if ci.owned_sql('SELECT (SELECT count(*) FROM ' + ci.NOTES + ' WHERE (sender_id IN (' + users
            + ') OR recipient_id IN (' + users + ') OR reply_to IN (' + notes + ')) AND note_id NOT IN (' + notes + ')) + '
            '(SELECT count(*) FROM ' + ci.MAILBOXES + ' WHERE (member_id IN (' + users + ') OR note_id IN (' + notes
            + ')) AND NOT ((member_id=' + str(CALLER) + ' AND note_id IN (' + notes + ')) OR (member_id='
            + str(OTHER) + ' AND note_id=' + literal(A) + '::uuid))) + '
            '(SELECT count(*) FROM ' + ci.REQUESTS + ' WHERE (sender_id IN (' + users + ') OR note_id IN (' + notes
            + ') OR request_id=' + literal(REQUEST_ID) + '::uuid) AND NOT (sender_id=' + str(CALLER)
            + ' AND request_id=' + literal(REQUEST_ID) + '::uuid)) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
            + ') OR blocked_id IN (' + users + ')) + (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + '))') != '0':
        raise SystemExit('F28 cleanup refuses unexpected note/mailbox/request/reply/block/deletion references')
    if ci.owned_sql('SELECT count(*) FROM pawbridge_user.users WHERE (user_id,email,nickname) IN ('
            + ','.join('(' + str(i) + ',' + literal('f28-' + str(i) + '@fixture.invalid') + ',' + literal(NAMES[i]) + ')' for i in USERS) + ')') != '2':
        raise SystemExit('F28 cleanup refuses changed owned User identities')
    ci.require_owner()
    ci.owned_sql('BEGIN; DELETE FROM ' + ci.REQUESTS + ' WHERE sender_id=' + str(CALLER) + ' AND request_id='
        + literal(REQUEST_ID) + '::uuid; DELETE FROM ' + ci.MAILBOXES + ' WHERE (member_id=' + str(CALLER)
        + ' AND note_id IN (' + notes + ')) OR (member_id=' + str(OTHER) + ' AND note_id=' + literal(A) + '::uuid); '
        'DELETE FROM ' + ci.NOTES + ' WHERE body=' + literal(BODY) + ' AND note_id=' + literal(C) + '::uuid; '
        'DELETE FROM ' + ci.NOTES + ' WHERE body=' + literal(BODY) + ' AND note_id IN (' + notes + '); '
        'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + '); '
        'DELETE FROM pawbridge_user.users WHERE user_id IN (' + users + '); COMMIT;')


def verify_view(body, kind, owned):
    def view(note, member):
        return dict(noteId=note, body=BODY, direction='SENT' if member == OTHER else 'INBOX',
                    createdAt=owned['created'], readAt=owned['read_at'], favorite=member == CALLER,
                    counterpartId=CALLER if member == OTHER else OTHER,
                    counterpartNickname=NAMES[CALLER if member == OTHER else OTHER], canReply=member == CALLER, contextHref=None)
    if kind == 'other_detail':
        wanted = view(A, OTHER)
    elif kind == 'inbox':
        wanted = dict(content=[view(C, CALLER)], totalElements=1, totalPages=1, number=0, size=10)
    elif kind in ('notifications_initial', 'notifications_read', 'notifications_empty'):
        selected = (C, A) if kind == 'notifications_initial' else (C,) if kind == 'notifications_read' else ()
        wanted = dict(content=[dict(noteId=n, kind='PRIVATE_NOTE', actorId=OTHER, actorNickname=NAMES[OTHER],
                                   createdAt=owned['created'], read=n == C, href='/notes/' + n) for n in selected],
                      nextCursor=None, unreadCount=1 if kind == 'notifications_initial' else 0)
    else:
        raise ValueError('F28 unknown fixed-fixture projection')
    actual = copy.deepcopy(body); data = actual.get('data')
    if type(data) is not dict:
        raise AssertionError('F28 expected an exact REST envelope')
    items = [data] if kind == 'other_detail' else data.get('content')
    if type(items) is not list:
        raise AssertionError('F28 REST content must remain an array')
    notification = kind.startswith('notifications_')
    for item in items:
        if (type(item.get('noteId')) is not str or type(item.get('actorId' if notification else 'counterpartId')) is not int
                or instant(item['createdAt'], True) != instant(owned['created'], True)):
            raise AssertionError('F28 identifier or createdAt wire value differs')
        item['createdAt'] = owned['created']
        if notification:
            if type(item.get('read')) is not bool:
                raise AssertionError('F28 notification read must remain boolean')
        else:
            if type(item.get('favorite')) is not bool or type(item.get('canReply')) is not bool:
                raise AssertionError('F28 surviving view flags must remain boolean')
            if instant(item['readAt'], True) != instant(owned['read_at'], True):
                raise AssertionError('F28 deletion changed or shifted surviving readAt')
            item['readAt'] = owned['read_at']
    numeric = ('unreadCount',) if notification else () if kind == 'other_detail' else ('totalElements', 'totalPages', 'number', 'size')
    if type(actual.get('code')) is not int or any(type(data.get(k)) is not int for k in numeric):
        raise AssertionError('F28 counts must remain raw JSON integers')
    if actual != dict(code=200, message=SUCCESS, data=wanted):
        raise AssertionError('F28 exact surviving detail/inbox/notification projection differs')


def run(ci):
    owner = ci.require_fixture()
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', owner=owner, source_sha=ci.SOURCE, runtime_sha=os.environ.get('GITHUB_SHA'),
                  timezone='Asia/Seoul', reader_pool_maximum=1, writer_pool_maximum=1,
                  protected_database_changes=0, s3_requests=0, token_evidence='two normal actual issuer tokens only',
                  permitted_delta='three own mailboxes, two orphan notes, one owned request link and one owned reply link')
    ci.EVIDENCE.mkdir(parents=True, exist_ok=True)
    receipt = ci.EVIDENCE / 'f28-packaged-result.txt'
    app_path, stub_path = ci.EVIDENCE / 'f28-packaged-startup.log', ci.EVIDENCE / 'f28-s3-stub.log'
    app = stub = owned = None
    streams, tokens = [], {}
    try:
        ci.require_measurements('jar')
        if tuple(ci.COUNTERS) != COUNTERS or ci.EXPECTED_JAR_COUNTS != EXPECTED_JAR_COUNTS:
            raise SystemExit('F28 runtime and prefix counter contracts differ')
        if not re.fullmatch('[0-9a-f]{40}', result['runtime_sha'] or ''):
            raise SystemExit('F28 runtime source commit is absent')
        jar = ci.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
        if not jar.is_file():
            raise SystemExit('F28 packaged application is absent')
        tokens = load_tokens(ci)
        baseline = ci.database_snapshot()
        if type(baseline) is not dict or len(baseline) != 17:
            raise SystemExit('F28 requires the full seventeen-table snapshot')
        bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
        base_env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if k in os.environ}
        app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'], F2_DB_USER=os.environ['F2_TEST_DB_USER'],
            F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'], F2_POOL_MAX_SIZE='1', F3_DB_WRITER_USER=ci.f3.WRITER,
            F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
            F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
            F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1')
        controls = {'X-F3-Owner': owner}
        deadline = time.monotonic() + JOURNEY_BUDGET

        def snapshot():
            if time.monotonic() >= deadline:
                raise AssertionError('F28 bounded journey deadline exceeded')
            state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
            return ci.database_snapshot(), state['counts'], state['objects'], len(state['requests'])

        def no_transaction():
            if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('F28 stream or completed request retained a database transaction')

        def check(path, *, method='GET', status=200, authenticated=True, identity=CALLER,
                  deleted=None, view=None, message=None, security=False, kind=None):
            if identity not in USERS:
                raise ValueError('F28 request actor is not an owned issuer principal')
            before = snapshot()
            headers = {'X-User-Id': str(OTHER if identity == CALLER else CALLER), 'X-User-Role': 'ROLE_ADMIN'}
            if authenticated:
                headers['Authorization'] = 'Bearer ' + load_tokens(ci)['F28_CALLER' if identity == CALLER else 'F28_OTHER']
            actual, raw, received = ci.f3.request(base, path, method=method, headers=headers)
            after = snapshot()
            if deleted is not None:
                changes = deletion_delta(before[0], after[0], deleted)
                for key, count in changes.items():
                    result[key] += count
                result['approved_database_write_checks'] += 1
            elif after[0] != before[0]:
                raise AssertionError('F28 failed/read request changed database rows')
            if after[1:] != before[1:]:
                raise AssertionError('F28 request contacted or changed S3')
            if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != (SECURITY_CACHE if security else 'no-store'):
                raise AssertionError('F28 status/cache/cookie differs')
            if status == 200 and method == 'DELETE':
                if raw:
                    raise AssertionError('F28 deletion must return an empty original 200')
            elif view is not None:
                verify_view(json.loads(raw), view, owned)
            elif json.loads(raw) != ({'code': status, 'message': message, 'data': None} if security else {'message': message}):
                raise AssertionError('F28 exact error envelope differs')
            no_transaction()
            result['request_checks'] += 1; result['nonstreaming_checks'] += 1
            result['delete_checks' if method == 'DELETE' else 'get_checks'] += 1
            if method == 'DELETE': result['successful_delete_checks' if status == 200 else 'rejected_delete_checks'] += 1
            if status == 404: result['not_found_checks'] += 1
            if kind: result[kind + '_checks'] += 1

        def opening(identity):
            before = snapshot()
            token = load_tokens(ci)['F28_CALLER' if identity == CALLER else 'F28_OTHER']
            stream = Stream(base, {'Authorization': 'Bearer ' + token, 'X-User-Id': str(OTHER if identity == CALLER else CALLER)})
            streams.append(stream); stream.wait('ready')
            if snapshot() != before:
                raise AssertionError('F28 SSE opening changed database rows or S3')
            no_transaction()
            result['request_checks'] += 1; result['get_checks'] += 1
            result['stream_open_checks'] += 1; result['stream_ready_checks'] += 1
            return stream

        def resync(count):
            caller.wait('resync', count); other.assert_resync(0); no_transaction()
            result['resync_checks'] += 1

        with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
            try:
                app, stub, base, endpoint, attempts, offset = ci.f4.start_ready_children(jar, app_env, base_env, owner,
                    bucket, prefix, stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                if ci.database_snapshot() != baseline: raise AssertionError('F28 startup changed business rows')
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']: raise AssertionError('F28 startup contacted S3')
                owned = seed(ci)
                caller, other = opening(CALLER), opening(OTHER)
                check('/api/v1/notes/notifications', view='notifications_initial', kind='notification')
                a_path, b_path, c_path = ('/api/v1/notes/' + n for n in NOTE_IDS)
                with ci.denied_privilege('mailbox-delete'):
                    check(a_path, method='DELETE', status=503, message=STORAGE, kind='storage_unavailable')
                caller.assert_resync(0); other.assert_resync(0)
                check(a_path, method='DELETE', deleted=A, kind='recovery'); resync(1)
                check(a_path, status=404, message=MISSING, kind='detail')
                check('/api/v1/notes', view='inbox', kind='list')
                check(a_path, identity=OTHER, view='other_detail', kind='detail')
                with ci.denied_privilege('note-delete'):
                    check(b_path, method='DELETE', status=503, message=STORAGE, kind='storage_unavailable')
                caller.assert_resync(1); other.assert_resync(0)
                check(b_path, method='DELETE', deleted=B, kind='recovery'); resync(2)
                check(b_path, status=404, message=MISSING, kind='detail')
                check('/api/v1/notes/notifications', view='notifications_read', kind='notification')
                check(b_path, method='DELETE', status=404, message=MISSING, kind='repeated_delete')
                caller.assert_resync(2); other.assert_resync(0)
                with ci.denied_privilege('nickname'):
                    check(c_path, method='DELETE', status=503, message=MEMBER_UNAVAILABLE, kind='member_unavailable')
                caller.assert_resync(2); other.assert_resync(0)
                check(c_path, method='DELETE', deleted=C, kind='recovery'); resync(3)
                check('/api/v1/notes/notifications', view='notifications_empty', kind='notification')
                check('/api/v1/notes/not-a-uuid', method='DELETE', authenticated=False, status=401,
                      security=True, message='인증 토큰이 필요합니다.', kind='authentication')
                check(c_path + '/extra', method='DELETE', status=403, security=True,
                      message='접근할 수 없습니다.', kind='adjacent_denied')
                ci.require_fixture()
                result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F28 packaged application'); app = None
                caller.wait_eof(); other.wait_eof(); caller.assert_resync(3); other.assert_resync(0)
                result['server_closed_open_streams'] = 2
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']: raise AssertionError('F28 shutdown contacted S3')
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F28 packaged application')
                finally:
                    try: close_streams(streams)
                    finally:
                        try:
                            if stub is not None: result['stub_sigterm_exit'] = ci.f3.stop_owned_child(stub, 'F28 S3 stub')
                        finally:
                            if owned is not None: cleanup(ci, owned)
        if ci.database_snapshot() != baseline: raise AssertionError('F28 owned cleanup did not restore all seventeen tables')
        ci.require_fixture()
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024: raise AssertionError('F28 child log budget exceeded')
        text = ci.f4.fresh_log(app_path, offset)
        if (text.count('Start completed.') != 2 or text.count('Shutdown completed.') != 2 or 'Graceful shutdown complete' not in text
                or text.count('Private note stream resources stopped') != 1 or 'Private note stream resources did not stop within budget' in text
                or result.get('server_closed_open_streams') != 2):
            raise AssertionError('F28 opened-SSE/two-pool normal shutdown evidence is incomplete')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, REQUEST_HASH, 'F28 synthetic', *tokens.values(), *NAMES.values(),
                     *('f28-' + str(i) + '@fixture.invalid' for i in USERS))
        all_app_text, stub_text = app_path.read_text(), stub_path.read_text()
        if any(v and (v in all_app_text or v in stub_text) for v in forbidden): raise AssertionError('F28 child log exposed a private input')
        if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F28 application connections remain after shutdown')
        if {k: result[k] for k in COUNTERS} != EXPECTED_JAR_COUNTS: raise AssertionError('F28 exact packaged journey counts differ')
        result.update(status='passed', shutdown_pools=2, remaining_application_connections=0,
                      stream_resources_stopped=True, owned_cleanup_restored=True)
    finally:
        receipt.write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F28 deletion/FK/resync/owned cleanup journey passed')
