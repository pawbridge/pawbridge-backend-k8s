#!/usr/bin/env python3
"""Hosted F27 favorite/resync probe. Import is inert; expected counts are not results.

Only the generic bounded transport and close-all helper come from F26. Expiry,
heartbeat, JWT variants and registry capacity are not reimplemented or retested.
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

CALLER, OTHER = 9981001, 9981002
USERS = (CALLER, OTHER)
INBOX, READ_INBOX, SENT = tuple('f2700000-0000-4000-8000-' + f'{n:012d}' for n in (1, 2, 3))
NOTE_IDS = (INBOX, READ_INBOX, SENT)
NAMES = {CALLER: 'F27caller9981001', OTHER: 'F27other9981002'}
BODY = 'F27 owned synthetic note'
STREAM_PATH = '/api/v1/notes/stream'
SUCCESS = '요청이 성공적으로 처리되었습니다.'
STORAGE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
SECURITY_CACHE = 'no-cache, no-store, max-age=0, must-revalidate'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'put_checks', 'successful_put_checks', 'rejected_put_checks',
            'get_checks', 'notification_checks', 'detail_checks', 'list_checks', 'stream_open_checks',
            'stream_ready_checks', 'resync_checks', 'other_resync_checks', 'storage_unavailable_checks',
            'member_unavailable_checks', 'recovery_checks', 'authentication_checks',
            'adjacent_denied_checks', 'approved_database_write_checks')
# Eighteen HTTP requests: GET8 (two SSE + six REST) and PUT10 (business8 + two security probes).
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (18, 16, 10, 6, 4, 8, 2, 1, 3, 2, 2, 6, 0, 1, 1, 2, 1, 1, 5)))
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
        raise SystemExit('F27 normal issuer token identity/signature/freshness is invalid') from None
    if not valid:
        raise SystemExit('F27 normal issuer token identity/signature/freshness differs')


def load_tokens(ci):
    owner = ci.f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    try:
        mode = path.lstat().st_mode
        if path.is_symlink() or path.resolve() != expected or not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise ValueError()
        values = json.loads(path.read_text())
        tokens = {name: values[name] for name in ('F27_CALLER', 'F27_OTHER')}
        secret = os.environ['F3_TEST_JWT_SECRET']
        if len(secret.encode('utf-8')) < 32:
            raise ValueError()
        for name, identity in (('F27_CALLER', CALLER), ('F27_OTHER', OTHER)):
            _claims(tokens[name], identity, secret)
    except (OSError, KeyError, TypeError, ValueError):
        raise SystemExit('F27 token input is not the owned regular 0600 issuer file') from None
    return tokens


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def instant(value, wire=False):
    ending = 'Z' if wire else r'(?:Z|[+-]\d{2}:\d{2})'
    if type(value) is not str or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?' + ending, value):
        raise AssertionError('F27 projection timestamp must be a microsecond ISO Instant')
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)


def seed(ci):
    ci.require_fixture()
    users, notes = ','.join(map(str, USERS)), ','.join(literal(n) + '::uuid' for n in NOTE_IDS)
    if ci.owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + ')) + '
            '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE note_id IN (' + notes + ') OR sender_id IN (' + users
            + ') OR recipient_id IN (' + users + ')) + (SELECT count(*) FROM ' + ci.MAILBOXES
            + ' WHERE member_id IN (' + users + ') OR note_id IN (' + notes + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
            + ') OR blocked_id IN (' + users + '))') != '0':
        raise SystemExit('F27 refuses nonempty reserved User/member/note/reference identities')
    now = datetime.now(timezone.utc)
    created = (now - timedelta(minutes=1)).replace(microsecond=123456).isoformat().replace('+00:00', 'Z')
    read_at = (now - timedelta(seconds=30)).replace(microsecond=654321).isoformat().replace('+00:00', 'Z')
    expires = (now + timedelta(hours=1)).isoformat().replace('+00:00', 'Z')
    user_rows = ','.join('(' + str(i) + ',' + literal(NAMES[i]) + ',' + literal('f27-' + str(i) + '@fixture.invalid')
                         + ",'F27 synthetic','GOOGLE','ROLE_USER')" for i in USERS)
    note_rows = ','.join('(' + literal(n) + '::uuid,' + str(CALLER if n == SENT else OTHER) + ','
                         + str(OTHER if n == SENT else CALLER) + ',' + literal(BODY) + ','
                         + literal(created) + '::timestamptz,' + literal(expires) + '::timestamptz)' for n in NOTE_IDS)
    boxes = ','.join('(' + str(CALLER) + ',' + literal(n) + '::uuid,' + literal('SENT' if n == SENT else 'INBOX')
                     + ',' + ('NULL' if n == INBOX else literal(read_at) + '::timestamptz')
                     + ',false,' + literal(created) + '::timestamptz)' for n in NOTE_IDS)
    ci.owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + user_rows + '; '
                 'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(' + str(CALLER) + '),(' + str(OTHER) + '); '
                 'INSERT INTO ' + ci.NOTES + '(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES' + note_rows + '; '
                 'INSERT INTO ' + ci.MAILBOXES + '(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + boxes + '; COMMIT;')
    return {'users': USERS, 'notes': NOTE_IDS, 'created': created, 'read_at': read_at}


def cleanup(ci, owned):
    if owned.get('users') != USERS or owned.get('notes') != NOTE_IDS:
        raise ValueError('F27 cleanup requires the acknowledged exact atomic seed')
    ci.require_owner()
    state = ci.database_snapshot()
    users, notes = ','.join(map(str, USERS)), ','.join(literal(n) + '::uuid' for n in NOTE_IDS)
    rows = [r for r in state['private_notes'] if r['note_id'] in NOTE_IDS]
    boxes = [r for r in state['private_note_mailboxes'] if r['note_id'] in NOTE_IDS or r['member_id'] in USERS]
    if (len(rows) != 3 or any(r['body'] != BODY or r['sender_id'] != (CALLER if r['note_id'] == SENT else OTHER)
            or r['recipient_id'] != (OTHER if r['note_id'] == SENT else CALLER) for r in rows)
            or len(boxes) != 3 or {(r['member_id'], r['note_id']) for r in boxes} != {(CALLER, n) for n in NOTE_IDS}):
        raise SystemExit('F27 cleanup refuses altered or unowned note/mailbox identities')
    if ci.owned_sql('SELECT (SELECT count(*) FROM ' + ci.NOTES + ' WHERE (sender_id IN (' + users
            + ') OR recipient_id IN (' + users + ')) AND note_id NOT IN (' + notes + ')) + '
            '(SELECT count(*) FROM ' + ci.NOTES + ' WHERE reply_to IN (' + notes + ') AND note_id NOT IN (' + notes + ')) + '
            '(SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id IN (' + users
            + ') OR blocked_id IN (' + users + ')) + (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN (' + users + '))') != '0':
        raise SystemExit('F27 cleanup refuses unexpected note/reply/block/deletion references')
    if ci.owned_sql('SELECT count(*) FROM pawbridge_user.users WHERE (user_id,email,nickname) IN ('
            + ','.join('(' + str(i) + ',' + literal('f27-' + str(i) + '@fixture.invalid') + ',' + literal(NAMES[i]) + ')' for i in USERS) + ')') != '2':
        raise SystemExit('F27 cleanup refuses changed owned User identities')
    ci.require_owner()
    ci.owned_sql('BEGIN; DELETE FROM ' + ci.MAILBOXES + ' WHERE member_id=' + str(CALLER) + ' AND note_id IN (' + notes + '); '
                 'DELETE FROM ' + ci.NOTES + ' WHERE body=' + literal(BODY) + ' AND note_id IN (' + notes + '); '
                 'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + users + '); '
                 'DELETE FROM pawbridge_user.users WHERE user_id IN (' + users + '); COMMIT;')


def favorite_delta(before, after, note, favorite, expected_change):
    if note not in NOTE_IDS or type(favorite) is not bool or type(expected_change) is not bool:
        raise ValueError('F27 permits only an explicit owned boolean favorite delta')
    expected = copy.deepcopy(before)
    rows = [r for r in expected['private_note_mailboxes'] if r['member_id'] == CALLER and r['note_id'] == note]
    found = [r for r in after['private_note_mailboxes'] if r['member_id'] == CALLER and r['note_id'] == note]
    if len(rows) != 1 or len(found) != 1 or type(rows[0]['favorite']) is not bool or type(found[0]['favorite']) is not bool:
        raise AssertionError('F27 favorite requires one exact owned boolean mailbox cell')
    changed = rows[0]['favorite'] is not favorite
    rows[0]['favorite'] = favorite
    if after != expected or changed != expected_change:
        raise AssertionError('F27 changed more than its exact favorite cell or missed the expected transition')
    return changed


def verify_view(body, kind, owned, favorites):
    def view(note):
        return dict(noteId=note, body=BODY, direction='SENT' if note == SENT else 'INBOX',
                    createdAt=owned['created'], readAt=None if note == INBOX else owned['read_at'],
                    favorite=favorites[note], counterpartId=OTHER, counterpartNickname=NAMES[OTHER],
                    canReply=note != SENT, contextHref=None)
    if kind == 'detail':
        wanted = view(INBOX)
    elif kind == 'notifications':
        wanted = dict(content=[dict(noteId=n, kind='PRIVATE_NOTE', actorId=OTHER, actorNickname=NAMES[OTHER],
                                    createdAt=owned['created'], read=n != INBOX, href='/notes/' + n)
                               for n in (READ_INBOX, INBOX)], nextCursor=None, unreadCount=1)
    elif kind in ('inbox_favorites', 'sent_favorites'):
        selected = [n for n in ((SENT,) if kind == 'sent_favorites' else (READ_INBOX, INBOX)) if favorites[n]]
        wanted = dict(content=[view(n) for n in selected], totalElements=len(selected), totalPages=1 if selected else 0, number=0, size=10)
    else:
        raise ValueError('F27 unknown fixed-fixture projection')
    actual = copy.deepcopy(body)
    data = actual.get('data')
    if type(data) is not dict:
        raise AssertionError('F27 expected an exact REST envelope')
    items = [data] if kind == 'detail' else data.get('content')
    if type(items) is not list:
        raise AssertionError('F27 REST content must remain an array')
    for item in items:
        if (type(item.get('noteId')) is not str
                or type(item.get('actorId' if kind == 'notifications' else 'counterpartId')) is not int
                or instant(item['createdAt'], True) != instant(owned['created'], True)):
            raise AssertionError('F27 identifier or original createdAt wire value differs')
        item['createdAt'] = owned['created']
        if kind == 'notifications':
            if type(item.get('read')) is not bool:
                raise AssertionError('F27 notification read must remain boolean')
        else:
            if type(item.get('favorite')) is not bool or type(item.get('canReply')) is not bool:
                raise AssertionError('F27 view flags must remain boolean')
            if item['noteId'] != INBOX:
                if instant(item['readAt'], True) != instant(owned['read_at'], True):
                    raise AssertionError('F27 favorite changed or shifted existing readAt')
                item['readAt'] = owned['read_at']
    numeric = ('unreadCount',) if kind == 'notifications' else () if kind == 'detail' else ('totalElements', 'totalPages', 'number', 'size')
    if type(actual.get('code')) is not int or any(type(data.get(k)) is not int for k in numeric):
        raise AssertionError('F27 counts must remain raw JSON integers')
    if actual != {'code': 200, 'message': SUCCESS, 'data': wanted}:
        raise AssertionError('F27 exact favorite/filter/read/unread projection differs')


def run(ci):
    owner = ci.require_fixture()
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', owner=owner, source_sha=ci.SOURCE, runtime_sha=os.environ.get('GITHUB_SHA'),
                  timezone='Asia/Seoul', pool_maximum=4,
                  protected_database_changes=0, s3_requests=0, token_evidence='two normal actual issuer tokens only',
                  permitted_delta='only the caller owned mailbox favorite boolean cell for each successful transition')
    ci.EVIDENCE.mkdir(parents=True, exist_ok=True)
    receipt = ci.EVIDENCE / 'f27-packaged-result.txt'
    app_path, stub_path = ci.EVIDENCE / 'f27-packaged-startup.log', ci.EVIDENCE / 'f27-s3-stub.log'
    app = stub = owned = None
    streams, tokens = [], {}
    try:
        ci.require_measurements('jar')
        if tuple(ci.COUNTERS) != COUNTERS or ci.EXPECTED_JAR_COUNTS != EXPECTED_JAR_COUNTS:
            raise SystemExit('F27 runtime and prefix counter contracts differ')
        if not re.fullmatch('[0-9a-f]{40}', result['runtime_sha'] or ''):
            raise SystemExit('F27 runtime source commit is absent')
        jar = ci.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
        if not jar.is_file():
            raise SystemExit('F27 packaged application is absent')
        tokens = load_tokens(ci)
        baseline = ci.database_snapshot()
        if type(baseline) is not dict or len(baseline) != 16:
            raise SystemExit('F27 requires the full sixteen-table snapshot')
        bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
        base_env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if k in os.environ}
        app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=ci.f3.APP,
            PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
            PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
            PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
            PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
        controls = {'X-F3-Owner': owner}
        favorites = dict.fromkeys(NOTE_IDS, False)
        deadline = time.monotonic() + JOURNEY_BUDGET

        def snapshot():
            if time.monotonic() >= deadline:
                raise AssertionError('F27 bounded journey deadline exceeded')
            state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
            return ci.database_snapshot(), state['counts'], state['objects'], len(state['requests'])

        def no_transaction():
            if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('F27 stream or completed request retained a database transaction')

        def check(path, *, method='GET', status=200, authenticated=True, favorite=None, note=None,
                  change=None, view=None, message=None, security=False, kind=None, raw_body=None):
            before = snapshot()
            headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
            if authenticated:
                headers['Authorization'] = 'Bearer ' + load_tokens(ci)['F27_CALLER']
            body = raw_body
            if favorite is not None:
                if type(favorite) is not bool:
                    raise ValueError('F27 sends canonical booleans only')
                body = json.dumps({'favorite': favorite}).encode('utf-8')
            if body is not None:
                headers['Content-Type'] = 'application/json'
            actual, raw, received = ci.f3.request(base, path, method=method, headers=headers, body=body)
            after = snapshot()
            if change is not None:
                changed = favorite_delta(before[0], after[0], note, favorite, change)
                favorites[note] = favorite
            else:
                changed = False
                if after[0] != before[0]:
                    raise AssertionError('F27 failed/read request changed database rows')
            if after[1:] != before[1:]:
                raise AssertionError('F27 request contacted or changed S3')
            if actual != status or received.get('Set-Cookie') or received.get('Cache-Control') != (SECURITY_CACHE if security else 'no-store'):
                raise AssertionError('F27 status/cache/cookie differs')
            if status == 200 and method == 'PUT':
                if raw:
                    raise AssertionError('F27 favorite must return an empty original 200')
            elif view is not None:
                verify_view(json.loads(raw), view, owned, favorites)
            elif json.loads(raw) != ({'code': status, 'message': message, 'data': None} if security else {'message': message}):
                raise AssertionError('F27 exact error envelope differs')
            no_transaction()
            result['request_checks'] += 1; result['nonstreaming_checks'] += 1
            result['put_checks' if method == 'PUT' else 'get_checks'] += 1
            if method == 'PUT': result['successful_put_checks' if status == 200 else 'rejected_put_checks'] += 1
            if kind: result[kind + '_checks'] += 1
            if changed: result['approved_database_write_checks'] += 1

        def opening(identity):
            before = snapshot()
            token = load_tokens(ci)['F27_CALLER' if identity == CALLER else 'F27_OTHER']
            stream = Stream(base, {'Authorization': 'Bearer ' + token, 'X-User-Id': str(OTHER if identity == CALLER else CALLER)})
            streams.append(stream); stream.wait('ready')
            if snapshot() != before:
                raise AssertionError('F27 SSE opening changed database rows or S3')
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
                if ci.database_snapshot() != baseline: raise AssertionError('F27 startup changed business rows')
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']: raise AssertionError('F27 startup contacted S3')
                owned = seed(ci)
                caller, other = opening(CALLER), opening(OTHER)
                check('/api/v1/notes/notifications', view='notifications', kind='notification')
                path = '/api/v1/notes/' + INBOX + '/favorite'
                check(path, method='PUT', favorite=True, note=INBOX, change=True); resync(1)
                check('/api/v1/notes/' + INBOX, view='detail', kind='detail')
                check('/api/v1/notes?favorites=true', view='inbox_favorites', kind='list')
                check(path, method='PUT', favorite=True, note=INBOX, change=False); resync(2)
                check(path, method='PUT', favorite=False, note=INBOX, change=True); resync(3)
                check('/api/v1/notes?favorites=true', view='inbox_favorites', kind='list')
                sent_path = '/api/v1/notes/' + SENT + '/favorite'
                check(sent_path, method='PUT', favorite=True, note=SENT, change=True); resync(4)
                check('/api/v1/notes?box=SENT&favorites=true', view='sent_favorites', kind='list')
                with ci.denied_privilege('favorite'):
                    check(sent_path, method='PUT', favorite=False, status=503, message=STORAGE, kind='storage_unavailable')
                caller.assert_resync(4); other.assert_resync(0)
                check(sent_path, method='PUT', favorite=False, note=SENT, change=True, kind='recovery'); resync(5)
                with ci.denied_privilege('nickname'):
                    check(path, method='PUT', favorite=True, status=503, message=MEMBER_UNAVAILABLE, kind='member_unavailable')
                caller.assert_resync(5); other.assert_resync(0)
                check(path, method='PUT', favorite=True, note=INBOX, change=True, kind='recovery'); resync(6)
                check('/api/v1/notes/notifications', view='notifications', kind='notification')
                check('/api/v1/notes/not-a-uuid/favorite', method='PUT', authenticated=False, status=401,
                      raw_body=b'{broken', security=True, message='인증 토큰이 필요합니다.', kind='authentication')
                check(path + '/extra', method='PUT', favorite=True, status=403, security=True,
                      message='접근할 수 없습니다.', kind='adjacent_denied')
                ci.require_fixture()
                # Two live normal streams remain open until actual normal server termination.
                result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F27 packaged application'); app = None
                caller.wait_eof(); other.wait_eof(); caller.assert_resync(6); other.assert_resync(0)
                result['server_closed_open_streams'] = 2
                state = ci.f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']: raise AssertionError('F27 shutdown contacted S3')
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = ci.f3.stop_owned_child(app, 'F27 packaged application')
                finally:
                    try: close_streams(streams)
                    finally:
                        try:
                            if stub is not None: result['stub_sigterm_exit'] = ci.f3.stop_owned_child(stub, 'F27 S3 stub')
                        finally:
                            if owned is not None: cleanup(ci, owned)
        if ci.database_snapshot() != baseline: raise AssertionError('F27 owned cleanup did not restore all sixteen tables')
        ci.require_fixture()
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024: raise AssertionError('F27 child log budget exceeded')
        text = ci.f4.fresh_log(app_path, offset)
        if (text.count('Start completed.') != 1 or text.count('Shutdown completed.') != 1 or 'Graceful shutdown complete' not in text
                or text.count('Private note stream resources stopped') != 1 or 'Private note stream resources did not stop within budget' in text
                or result.get('server_closed_open_streams') != 2):
            raise AssertionError('F27 opened-SSE/single-pool normal shutdown evidence is incomplete')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, 'F27 synthetic', *tokens.values(), *NAMES.values(), *('f27-' + str(i) + '@fixture.invalid' for i in USERS))
        all_app_text, stub_text = app_path.read_text(), stub_path.read_text()
        if any(v and (v in all_app_text or v in stub_text) for v in forbidden): raise AssertionError('F27 child log exposed a private input')
        if ci.owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F27 application connections remain after shutdown')
        if {k: result[k] for k in COUNTERS} != EXPECTED_JAR_COUNTS: raise AssertionError('F27 exact packaged journey counts differ')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0,
                      stream_resources_stopped=True, owned_cleanup_restored=True)
    finally:
        receipt.write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F27 favorite/resync/filter/read-state/owned cleanup journey passed')
