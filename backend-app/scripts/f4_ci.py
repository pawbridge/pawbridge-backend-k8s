#!/usr/bin/env python3
"""Hosted-only F4 checks in the same disposable F2/F3 PostgreSQL and synthetic S3."""
import json
import os
import pathlib
import re
import subprocess
import sys
import uuid

import f2_ci as f2
import f3_ci as f3

ROOT = pathlib.Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / 'build/ci'
MARKER = 'pawbridge-f4-ci-only-v1'


def require_fixture():
    owner = f2.require_hosted()
    f3.require_fixture()
    if os.environ.get('F4_CI_OWNER') != owner:
        raise SystemExit('F4 fixture owner mismatch')
    if f3.sql('SELECT value || \':\' || owner FROM pawbridge_f4_fixture.marker') != MARKER + ':' + owner:
        raise SystemExit('F4 fixture identity mismatch')
    return owner


def prepare():
    owner = f2.require_hosted()
    f3.require_fixture()
    f2.export_environment({'F4_CI_OWNER': owner})
    f3.sql(input_text=(ROOT / 'scripts/f4_fixture.sql').read_text())
    require_fixture()
    print('Extended the same owned PG with timestamp-only post updates and search deletion')


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def distinct_ports():
    first = f3.port()
    for _ in range(8):
        second = f3.port()
        if second != first:
            return first, second
    raise AssertionError('Could not select distinct F4 listener ports')


def fresh_log(path, offset):
    return path.read_bytes()[offset:].decode('utf-8', errors='replace')


def stub_ready(process, log, offset, endpoint, owner):
    if process.poll() is not None:
        return False
    started = False
    for line in fresh_log(log, offset).splitlines():
        try:
            metadata = json.loads(line)
        except ValueError:
            continue
        if isinstance(metadata, dict) and metadata.get('endpoint') == endpoint and metadata.get('owner') == owner:
            started = True
            break
    return started and f3.expect(endpoint, '/__f3__/state', 200, headers={'X-F3-Owner': owner})['owner'] == owner


def application_ready(process, log, offset, port, base):
    # An unrelated listener returning 404 is not evidence that our child bound its port.
    return (process.poll() is None
        and ('Tomcat started on port ' + str(port) + ' ') in fresh_log(log, offset)
        and f3.request(base, '/api/v1/posts/read/99999999')[0] == 404)


def confirmed_bind_failure(failure, process, text):
    if (not isinstance(failure, AssertionError) or not str(failure).endswith(' exited before readiness')
            or process is None or process.poll() in (None, 0)):
        return False
    return ('[Errno 98] Address already in use' in text
        or 'java.net.BindException: Address already in use' in text
        or re.search(r'Port [0-9]+ was already in use\.', text) is not None)


def stop_startup_child(process, label):
    if process is not None:
        if process.poll() is None:
            # This helper raises after a forced kill; never turn forced cleanup into success/retry.
            f3.stop_owned_child(process, label)
        else:
            process.wait(timeout=5)


def start_ready_children(jar, app_env, base_env, owner, bucket, prefix,
                         stub_log, app_log, stub_path, app_path):
    if owner != f2.require_hosted():
        raise SystemExit('F4 startup owner mismatch')
    for attempt in range(1, 4):
        stub_port, app_port = distinct_ports()
        endpoint = 'http://127.0.0.1:' + str(stub_port)
        base = 'http://127.0.0.1:' + str(app_port)
        stub_offset, app_offset = len(stub_path.read_bytes()), len(app_path.read_bytes())
        stub = app = None
        try:
            stub = subprocess.Popen([sys.executable, str(ROOT / 'scripts/f3_s3_stub.py'), '--host', '127.0.0.1',
                '--port', str(stub_port), '--bucket', bucket, '--prefix', prefix,
                '--owner', owner, '--allow-delete'], env=dict(base_env, GITHUB_ACTIONS='true',
                RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux', GITHUB_REPOSITORY=os.environ['GITHUB_REPOSITORY'],
                GITHUB_RUN_ID=os.environ['GITHUB_RUN_ID'], GITHUB_RUN_ATTEMPT=os.environ['GITHUB_RUN_ATTEMPT']),
                stdout=stub_log, stderr=subprocess.STDOUT)
            f3.wait_ready(stub, lambda: stub_ready(stub, stub_path, stub_offset, endpoint, owner), 'F4 S3 stub')
            f3.verify_continue_handshake(endpoint, owner)
            app = subprocess.Popen(['java', '-Duser.timezone=Asia/Seoul', '-Xmx256m', '-jar', str(jar),
                '--spring.main.banner-mode=off'],
                env=dict(app_env, F2_PORT=str(app_port), F3_STORAGE_ENDPOINT=endpoint),
                stdout=app_log, stderr=subprocess.STDOUT)
            f3.wait_ready(app, lambda: application_ready(app, app_path, app_offset, app_port, base),
                          'F4 packaged application')
            return app, stub, base, endpoint, attempt, app_offset
        except BaseException as failure:
            try:
                retry = (confirmed_bind_failure(failure, stub, fresh_log(stub_path, stub_offset))
                         or confirmed_bind_failure(failure, app, fresh_log(app_path, app_offset)))
            finally:
                try:
                    stop_startup_child(app, 'F4 startup application')
                finally:
                    stop_startup_child(stub, 'F4 startup S3 stub')
            if not retry or attempt == 3:
                raise


def prepare_business_rows():
    # Called only after both owned listeners are ready, and after unchanged F3 smoke.
    f3.sql('TRUNCATE pawbridge_community.post_search_documents,pawbridge_community.outbox_events,'
           'pawbridge_community.posts,pawbridge_user.users; '
           "INSERT INTO pawbridge_user.users VALUES(42,'합성작성자'); "
           "SELECT setval('pawbridge_community.posts_post_id_seq',100000,true); "
           'CREATE TABLE pawbridge_f4_fixture.comments(comment_id BIGINT PRIMARY KEY,'
           'post_id BIGINT REFERENCES pawbridge_community.posts(post_id)); '
           'CREATE TABLE pawbridge_f4_fixture.private_notes(note_id BIGINT PRIMARY KEY,'
           'post_id BIGINT REFERENCES pawbridge_community.posts(post_id))')


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    for token in tokens.values():
        print('::add-mask::' + token)
    endpoint = ''
    bucket = 'f3-' + owner
    prefix = bucket + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR')
                if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'],
        F2_DB_USER=os.environ['F2_TEST_DB_USER'], F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'],
        F2_POOL_MAX_SIZE='2', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid',
        F3_STORAGE_OBJECT_PREFIX=prefix, F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access',
        F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1')
    base = ''
    state = lambda: f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
    result = {'timezone': 'Asia/Seoul', 'reader_pool_maximum': 2, 'writer_pool_maximum': 1,
              'synthetic_database': f2.DATABASE, 'status': 'incomplete'}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    logs = EVIDENCE / 'f4-packaged-startup.log'

    def snapshot(post_id):
        return json.loads(f3.sql('SELECT row_to_json(p) FROM pawbridge_community.posts p WHERE post_id=' + str(post_id)))

    def business(post_id):
        return (snapshot(post_id), f3.sql('SELECT COALESCE(json_agg(e ORDER BY outbox_id),\'[]\'::json) '
            'FROM pawbridge_community.outbox_events e WHERE aggregate_id=' + literal(post_id)),
            f3.sql('SELECT COALESCE(json_agg(d),\'[]\'::json) FROM pawbridge_community.post_search_documents d '
                   'WHERE post_id=' + str(post_id)))

    def delete(post_id, expected=200, token=None, extra=None):
        headers = dict(extra or {})
        if token is not None:
            headers['Authorization'] = 'Bearer ' + token
        response = f3.expect(base, '/api/v1/posts/' + str(post_id), expected, 'DELETE', headers)
        if response['code'] != expected or response['data'] is not None:
            raise AssertionError('DELETE envelope code/data changed')
        messages = {200: '게시글이 삭제되었습니다.', 403: '게시글에 접근할 권한이 없습니다.',
                    404: '게시글을 찾을 수 없습니다.'}
        if expected in messages and response['message'] != messages[expected]:
            raise AssertionError('DELETE envelope message changed')
        return response

    def create(files=(), board='COMMUNICATION', token=None):
        body, content_type = f3.multipart({'title': 'F4 합성 제목', 'content': 'F4 합성 본문', 'boardType': board}, files)
        response = f3.expect(base, '/api/v1/posts', 200, 'POST',
            {'Authorization': 'Bearer ' + (token or tokens['ROLE_USER']), 'Content-Type': content_type}, body)
        return response['data']

    def deleted(post_id, original):
        now = snapshot(post_id)
        for key in ('post_id', 'author_id', 'title', 'content', 'board_type', 'image_urls', 'created_at'):
            if now[key] != original[key]:
                raise AssertionError('Soft deletion changed a retained post field: ' + key)
        if now['deleted_at'] is None or now['updated_at'] is None:
            raise AssertionError('Soft-delete timestamps are missing')
        events = json.loads(f3.sql('SELECT COALESCE(json_agg(e),\'[]\'::json) '
            'FROM pawbridge_community.outbox_events e WHERE type=\'POST_DELETED\' AND aggregate_id=' + literal(post_id)))
        if len(events) != 1:
            raise AssertionError('Expected exactly one POST_DELETED event')
        event = events[0]
        uuid.UUID(event['event_id'])
        if (event['aggregate_type'] != 'Post' or event['aggregate_id'] != str(post_id)
                or event['payload'] != {'postId': post_id} or event['created_at'] is None):
            raise AssertionError('POST_DELETED event fields or exact payload changed')
        if f3.sql('SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id=' + str(post_id)) != '0':
            raise AssertionError('Soft deletion did not remove its search document')
        absent = f3.expect(base, '/api/v1/posts/read/' + str(post_id), 404)
        if absent['data'] is not None or absent['message'] != '게시글을 찾을 수 없습니다.':
            raise AssertionError('F2 public GET did not hide the deleted post')
        before = business(post_id), state()['counts']
        delete(post_id, 404, tokens['ROLE_USER'])
        if (business(post_id), state()['counts']) != before:
            raise AssertionError('Repeated deletion changed DB rows or contacted storage')

    with (EVIDENCE / 'f4-s3-stub.log').open('w') as stub_log, logs.open('w') as app_log:
        stub = app = None
        try:
            app, stub, base, endpoint, attempts, app_offset = start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log,
                EVIDENCE / 'f4-s3-stub.log', logs)
            result['startup_attempts'] = attempts
            result['confirmed_bind_retries'] = attempts - 1
            prepare_business_rows()
            normal = create([('one.jpg', 'image/jpeg', b'F4-image'), ('two.mp4', 'video/mp4', b'F4-video')])
            post_id = normal['postId']
            first, second = normal['imageUrls']
            # Valid duplicate/null order and rejected URL scopes come from stored DB values.
            urls = [first, None, first, second, 'https://outside.invalid/no-object',
                    'https://fixture.invalid/other-run/posts/images/00000000-0000-0000-0000-000000000000.jpg',
                    'https://fixture.invalid/' + prefix + 'posts/images/%2e%2e/not-an-object']
            f3.sql('UPDATE pawbridge_community.posts SET image_urls=' + literal(json.dumps(urls)) + '::json '
                'WHERE post_id=' + str(post_id))
            stable = business(post_id), state()['counts']
            for name in ('refresh', 'expired', 'forged', 'missingId', 'zeroId', 'negativeId', 'stringId',
                         'missingRole', 'numericRole'):
                delete(post_id, 401, tokens[name], {'X-User-Id': '42', 'X-User-Role': 'ROLE_ADMIN'})
            delete(post_id, 401, extra={'X-User-Id': '42'})
            if (business(post_id), state()['counts']) != stable:
                raise AssertionError('Authentication rejection caused DB or storage effects')
            foreign = create()
            f3.sql('UPDATE pawbridge_community.posts SET author_id=43 WHERE post_id=' + str(foreign['postId']))
            foreign_stable = business(foreign['postId']), state()['counts']
            for role in ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER'):
                delete(foreign['postId'], 403, tokens[role])
            if (business(foreign['postId']), state()['counts']) != foreign_stable:
                raise AssertionError('Another author or ADMIN bypass caused DB or storage effects')
            missing_stable = f3.row_counts(), state()['counts']
            for missing in (99999999, 0, -1):
                delete(missing, 404, tokens['ROLE_USER'])
            if (f3.row_counts(), state()['counts']) != missing_stable:
                raise AssertionError('Missing post rejection caused DB or storage effects')
            f3.sql('INSERT INTO pawbridge_f4_fixture.comments VALUES(1,' + str(post_id) + '); '
                   'INSERT INTO pawbridge_f4_fixture.private_notes VALUES(1,' + str(post_id) + ')')
            original = snapshot(post_id)
            before = state()
            delete(post_id, token=tokens['ROLE_USER'], extra={'X-User-Id': '43'})
            deleted(post_id, original)
            after = state()
            deleted_requests = after['requests'][len(before['requests']):]
            expected_keys = [url.removeprefix('https://fixture.invalid/') for url in (first, first, second)]
            if (after['counts']['DELETE'] != before['counts']['DELETE'] + 3 or len(after['objects']) != 0
                    or [entry['key'] for entry in deleted_requests] != expected_keys
                    or any(entry['method'] != 'DELETE' or entry['status'] != 204 for entry in deleted_requests)):
                raise AssertionError('Stored URL order/duplicate handling or scoped SDK DELETE changed')
            if (f3.sql('SELECT count(*) FROM pawbridge_f4_fixture.comments') != '1'
                    or f3.sql('SELECT count(*) FROM pawbridge_f4_fixture.private_notes') != '1'):
                raise AssertionError('Soft deletion removed synthetic references')
            if f3.sql('SELECT count(*) FROM pawbridge_f4_fixture.private_notes n JOIN pawbridge_community.posts p '
                      'USING(post_id) WHERE p.deleted_at IS NULL') != '0':
                raise AssertionError('Deleted post remained an active synthetic note context')
            result['synthetic_reference_rows_retained'] = True
            # Normal owner deletion covers all five board values; POST's restrictions stay intact.
            for role, board in (('ROLE_ADMIN', 'ADOPTION'), ('ROLE_SHELTER', 'PROTECTION'),
                                ('ROLE_USER', 'MISSING'), ('ROLE_USER', 'REPORT')):
                item = create(board=board if board in ('ADOPTION', 'PROTECTION') else 'COMMUNICATION')
                if board in ('MISSING', 'REPORT'):
                    f3.sql('UPDATE pawbridge_community.posts SET board_type=' + literal(board)
                           + ' WHERE post_id=' + str(item['postId']))
                original = snapshot(item['postId'])
                delete(item['postId'], token=tokens[role])
                deleted(item['postId'], original)
            # A permanent synthetic SDK error is per URL; the next URL and DB still proceed.
            item = create([('keep.jpg', 'image/jpeg', b'keep'), ('remove.jpg', 'image/jpeg', b'remove')])
            original = snapshot(item['postId'])
            before = state()
            f3.expect(endpoint, '/__f3__/fail-next-delete', 200, 'POST',
                dict(controls, **{'Content-Type': 'application/json'}), b'{"after":0,"count":1,"status":400}')
            delete(item['postId'], token=tokens['ROLE_USER'])
            deleted(item['postId'], original)
            after = state()
            delete_requests = after['requests'][len(before['requests']):]
            expected_keys = [url.removeprefix('https://fixture.invalid/') for url in item['imageUrls']]
            if (after['counts']['DELETE'] != before['counts']['DELETE'] + 2
                    or len(after['objects']) != len(before['objects']) - 1
                    or [entry['key'] for entry in delete_requests] != expected_keys
                    or [entry['status'] for entry in delete_requests] != [400, 204]
                    or expected_keys[0] not in {entry['key'] for entry in after['objects']}
                    or expected_keys[1] in {entry['key'] for entry in after['objects']}):
                raise AssertionError('SDK failure stopped later URLs or retried the DELETE')
            for table, permission in (('outbox_events', 'INSERT'), ('post_search_documents', 'DELETE')):
                item = create([('rollback.jpg', 'image/jpeg', b'irreversible-object')])
                original = snapshot(item['postId'])
                stable = business(item['postId'])
                before = state()
                f3.sql('REVOKE ' + permission + ' ON pawbridge_community.' + table + ' FROM ' + f3.WRITER)
                try:
                    delete(item['postId'], 500, tokens['ROLE_USER'])
                finally:
                    f3.sql('GRANT ' + permission + ' ON pawbridge_community.' + table + ' TO ' + f3.WRITER)
                after = state()
                if (business(item['postId']) != stable or after['counts']['DELETE'] != before['counts']['DELETE'] + 1
                        or len(after['objects']) != len(before['objects']) - 1):
                    raise AssertionError('Confirmed rollback changed DB or restored/retried an external deletion')
                f3.expect(base, '/api/v1/posts/read/' + str(item['postId']), 200)
                delete(item['postId'], token=tokens['ROLE_USER'])
                deleted(item['postId'], original)
            delete(foreign['postId'], 401)  # An earlier authenticated request cannot leak its principal.
            if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('F4 HTTP response leaked an application transaction')
            result['packaged_post_delete_get_auth_best_effort_rollback_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F4 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F4 S3 stub')
                (EVIDENCE / 'f4-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    text = fresh_log(logs, app_offset)
    if text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F4 pool and HTTP graceful termination evidence is missing')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F4 application database connections remain after termination')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    (EVIDENCE / 'f4-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted synthetic F4 owner deletion, best-effort SDK, rollback/recovery and graceful shutdown passed')


def cleanup():
    # F3 delegates to the original ownership-checked F2 container cleanup.
    f3.cleanup()


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke, 'cleanup': cleanup}[sys.argv[1]]()
