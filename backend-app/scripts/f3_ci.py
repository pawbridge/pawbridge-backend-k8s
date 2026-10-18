#!/usr/bin/env python3
"""Hosted-only F3 extension of the single owned F2 PostgreSQL fixture."""
import contextlib
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
import hashlib
import json
import os
import pathlib
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

import f2_ci as f2

ROOT = pathlib.Path(__file__).resolve().parent.parent
MARKER = 'pawbridge-f3-ci-only-v1'
WRITER = 'pawbridge_f3_writer'
EVIDENCE = ROOT / 'build/ci'


def sql(query='', input_text=None):
    f2.require_fixture()
    environment = dict(os.environ, PGPASSWORD=os.environ['F2_TEST_ADMIN_PASSWORD'])
    args = ['docker', 'exec', '--env', 'PGPASSWORD']
    if input_text is not None:
        args += ['--interactive', '--env', 'F3_TEST_WRITER_PASSWORD', '--env', 'F3_CI_OWNER']
    args += [os.environ['F2_CI_CONTAINER'], 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
             '-U', 'postgres', '-d', f2.DATABASE, '-At']
    if input_text is None:
        args += ['-c', query]
    return f2.command(args, env=environment, input=input_text, capture_output=True).stdout.strip()


def require_fixture():
    owner = f2.require_hosted()
    f2.require_fixture()
    if os.environ.get('F3_CI_OWNER') != owner or os.environ.get('F3_TEST_WRITER_USER') != WRITER:
        raise SystemExit('F3 fixture owner or writer mismatch')
    if sql("SELECT value || ':' || owner FROM pawbridge_f3_fixture.marker") != MARKER + ':' + owner:
        raise SystemExit('F3 fixture identity mismatch')
    token_file = pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).resolve()
    if token_file != pathlib.Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json'):
        raise SystemExit('F3 token file must belong to this hosted run')


def prepare():
    owner = f2.require_hosted()
    f2.require_fixture()
    password = secrets.token_urlsafe(24)
    secret = secrets.token_urlsafe(48)
    print('::add-mask::' + password)
    print('::add-mask::' + secret)
    f2.export_environment({'F3_CI_OWNER': owner, 'F3_TEST_WRITER_USER': WRITER,
        'F3_TEST_WRITER_PASSWORD': password, 'F3_TEST_JWT_SECRET': secret,
        'F3_TEST_TOKENS_PATH': str(pathlib.Path(os.environ['RUNNER_TEMP']).resolve()
                                  / ('f3-tokens-' + owner + '.json'))})
    sql(input_text=(ROOT / 'scripts/f3_fixture.sql').read_text())
    require_fixture()
    print('Extended the same owned PG fixture with three Community write tables and a minimal writer')


def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def request(base, path, method='GET', headers=None, body=None):
    req = urllib.request.Request(base + path, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def expect(base, path, status, method='GET', headers=None, body=None):
    actual, data, response_headers = request(base, path, method, headers, body)
    if actual != status:
        raise AssertionError('Unexpected HTTP status for ' + method + ' ' + path + ': ' + str(actual))
    if response_headers.get('Set-Cookie'):
        raise AssertionError('Candidate must not establish a session')
    return json.loads(data) if data else None


def multipart(fields, files=()):
    boundary = 'F3SyntheticBoundary'
    parts = []
    for key, value in fields.items():
        parts.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="'
            + key + '"\r\n\r\n' + value + '\r\n').encode())
    for name, mime, content in files:
        parts.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="files"; filename="'
            + name + '"\r\nContent-Type: ' + mime + '\r\n\r\n').encode() + content + b'\r\n')
    parts.append(('--' + boundary + '--\r\n').encode())
    return b''.join(parts), 'multipart/form-data; boundary=' + boundary


def row_counts():
    return sql("SELECT (SELECT count(*) FROM pawbridge_community.posts),"
        "(SELECT count(*) FROM pawbridge_community.outbox_events),"
        "(SELECT count(*) FROM pawbridge_community.post_search_documents)")


def wait_ready(process, check, label):
    deadline = time.monotonic() + 45
    while True:
        if process.poll() is not None:
            raise AssertionError(label + ' exited before readiness')
        try:
            if check():
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError(label + ' readiness deadline exceeded')
        time.sleep(0.25)


def verify_continue_handshake(endpoint, owner):
    host, raw_port = endpoint.removeprefix('http://').rsplit(':', 1)
    if host != '127.0.0.1':
        raise AssertionError('Handshake probe must remain loopback')
    with socket.create_connection((host, int(raw_port)), timeout=3) as connection:
        connection.sendall(('GET /__f3__/state HTTP/1.1\r\nHost: 127.0.0.1\r\n'
            'X-F3-Owner: ' + owner + '\r\nExpect: 100-continue\r\n'
            'Content-Length: 0\r\nConnection: close\r\n\r\n').encode())
        response = bytearray()
        while len(response) < 16384:
            chunk = connection.recv(4096)
            if not chunk:
                break
            response.extend(chunk)
        if (not response.startswith(b'HTTP/1.1 100 Continue\r\n')
                or b'HTTP/1.1 200 OK\r\n' not in response):
            raise AssertionError('Synthetic server did not send the required interim response')


def stop_owned_child(process, label):
    if process.poll() is not None:
        raise AssertionError(label + ' exited before requested normal termination')
    process.send_signal(signal.SIGTERM)
    try:
        result = process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
        raise AssertionError(label + ' required forced termination of this CI-owned child')
    if result not in (0, 143, -signal.SIGTERM):
        raise AssertionError(label + ' normal termination failed: ' + str(result))
    return result


def smoke():
    require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    if set(tokens) != {'ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER', 'refresh', 'expired', 'forged',
                      'missingId', 'zeroId', 'negativeId', 'stringId', 'missingRole', 'numericRole'}:
        raise SystemExit('Pinned legacy issuer token fixture is incomplete')
    for token in tokens.values():
        print('::add-mask::' + token)
    sql("TRUNCATE pawbridge_community.post_search_documents,pawbridge_community.outbox_events,"
        "pawbridge_community.posts,pawbridge_user.users; "
        "INSERT INTO pawbridge_user.users VALUES(42,'합성작성자'); "
        "INSERT INTO pawbridge_community.posts VALUES(42,42,'합성제목','합성내용','REPORT',"
        "'[\"https://fixture.invalid/가.jpg\",null,\"https://fixture.invalid/가.jpg\"]',NULL,"
        "'2026-10-17 15:01:02.123456','2026-10-17 16:02:00'); "
        "SELECT setval('pawbridge_community.posts_post_id_seq',"
        "(SELECT max(post_id) FROM pawbridge_community.posts),true)")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    owner = f2.require_hosted()
    endpoint = 'http://127.0.0.1:' + str(port())
    bucket = 'f3-' + owner
    prefix = bucket + '/'
    control_headers = {'X-F3-Owner': owner}
    # Explicit child environments exclude CI secrets and admin fixture credentials.
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR')
                if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'],
        F2_DB_USER=os.environ['F2_TEST_DB_USER'], F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'],
        F2_POOL_MAX_SIZE='2', F2_PORT=str(port()), F3_DB_WRITER_USER=WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], F3_STORAGE_ENDPOINT=endpoint,
        F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid',
        F3_STORAGE_OBJECT_PREFIX=prefix, F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access',
        F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1')
    base = 'http://127.0.0.1:' + app_env['F2_PORT']
    state = lambda: expect(endpoint, '/__f3__/state', 200, headers=control_headers)
    logs = EVIDENCE / 'f3-packaged-startup.log'
    result = {'timezone': 'Asia/Seoul', 'reader_pool_maximum': 2, 'writer_pool_maximum': 1,
              'synthetic_database': f2.DATABASE, 'baseline': 'F2 compatibility tests run separately'}
    with (EVIDENCE / 'f3-s3-stub.log').open('w') as stub_log, logs.open('w') as app_log:
        stub = subprocess.Popen([sys.executable, str(ROOT / 'scripts/f3_s3_stub.py'), '--host', '127.0.0.1',
            '--port', endpoint.rsplit(':', 1)[1], '--bucket', bucket, '--prefix', prefix, '--owner', owner],
            env=dict(base_env, GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux',
                     GITHUB_REPOSITORY=os.environ['GITHUB_REPOSITORY'], GITHUB_RUN_ID=os.environ['GITHUB_RUN_ID'],
                     GITHUB_RUN_ATTEMPT=os.environ['GITHUB_RUN_ATTEMPT']), stdout=stub_log, stderr=subprocess.STDOUT)
        app = None
        try:
            wait_ready(stub, lambda: state()['owner'] == owner, 'S3 stub')
            verify_continue_handshake(endpoint, owner)
            for missing_key in ('F2_JDBC_URL', 'F3_DB_WRITER_PASSWORD', 'F3_JWT_SECRET', 'F3_STORAGE_ENDPOINT'):
                incomplete = {key: value for key, value in app_env.items() if key != missing_key}
                failure = subprocess.run(['java', '-Xmx256m', '-jar', str(jar), '--spring.main.banner-mode=off'],
                    env=incomplete, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, text=True)
                if (failure.returncode == 0 or missing_key not in failure.stdout
                        or 'Tomcat started' in failure.stdout or 'Starting...' in failure.stdout):
                    raise AssertionError('Missing setting did not fail before resources/listener: ' + missing_key)
                (EVIDENCE / ('f3-missing-' + missing_key + '.log')).write_text(failure.stdout)
            if state()['counts']['PUT'] != 0:
                raise AssertionError('Fail-fast startup contacted storage')
            if sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
                raise AssertionError('Fail-fast startup opened an application database connection')
            app = subprocess.Popen(['java', '-Duser.timezone=Asia/Seoul', '-Xmx256m', '-jar', str(jar),
                '--spring.main.banner-mode=off'], env=app_env, stdout=app_log, stderr=subprocess.STDOUT)
            wait_ready(app, lambda: request(base, '/api/v1/posts/read/42')[0] == 200, 'Packaged application')
            read = expect(base, '/api/v1/posts/read/42', 200, headers={'Authorization': 'Bearer invalid'})
            if (read['code'] != 200 or read['message'] != '요청이 성공적으로 처리되었습니다.'
                    or read['data']['authorNickname'] != '합성작성자'
                    or read['data']['imageUrls'] != ['https://fixture.invalid/가.jpg', None, 'https://fixture.invalid/가.jpg']
                    or read['data']['createdAt'] != '2026-10-18T00:01:02.123456'
                    or read['data']['updatedAt'] != '2026-10-18T01:02:00'):
                raise AssertionError('F2 public read/date/JSON contract changed')
            fields = {'title': '한글 강아지 입양', 'content': '보호소 반려동물 이야기', 'boardType': 'COMMUNICATION'}
            def post(token, status=200, change=None, files=(), extra=None):
                body, content_type = multipart(dict(fields, **(change or {})), files)
                headers = {'Content-Type': content_type, **(extra or {})}
                if token is not None:
                    headers['Authorization'] = 'Bearer ' + token
                return expect(base, '/api/v1/posts', status, 'POST', headers, body)
            no_effect = row_counts()
            for token_name in ('refresh', 'expired', 'forged', 'missingId', 'zeroId', 'negativeId',
                               'stringId', 'missingRole', 'numericRole'):
                denied = post(tokens[token_name], 401, files=[('a.jpg', 'image/jpeg', b'synthetic')])
                expected_message = ('유효하지 않거나 만료된 토큰입니다.' if token_name in ('expired', 'forged')
                                    else '토큰 파싱에 실패했습니다.')
                if denied['data'] is not None or denied['code'] != 401 or denied['message'] != expected_message:
                    raise AssertionError('Authentication envelope changed')
            missing_auth = post(None, 401, extra={'X-User-Id': '42', 'X-User-Role': 'ROLE_ADMIN'},
                                files=[('a.jpg', 'image/jpeg', b'synthetic')])
            if missing_auth['message'] != '인증 토큰이 필요합니다.':
                raise AssertionError('Missing authentication envelope changed')
            if row_counts() != no_effect or state()['counts']['PUT'] != 0:
                raise AssertionError('Invalid authentication had observable storage/database effects')
            for board in ('MISSING', 'REPORT'):
                denied = post(tokens['ROLE_USER'], 400, {'boardType': board}, [('a.jpg', 'image/jpeg', b'synthetic')])
                if denied['message'] != '실종·목격 제보는 전용 등록 화면에서 작성해 주세요.':
                    raise AssertionError('Report guard message changed')
            if row_counts() != no_effect or state()['counts']['PUT'] != 0:
                raise AssertionError('Report rejection had side effects')
            body, content_type = multipart({'content': '필수파라미터', 'boardType': 'COMMUNICATION'})
            missing_title = expect(base, '/api/v1/posts', 400, 'POST',
                {'Authorization': 'Bearer ' + tokens['ROLE_USER'], 'Content-Type': content_type}, body)
            if missing_title['message'] != "필수 요청 파라미터 'title'(이)가 누락되었습니다.":
                raise AssertionError('Missing multipart parameter contract changed')
            post(tokens['ROLE_USER'], 500, {'boardType': 'NOT_A_BOARD'})
            post(tokens['ROLE_USER'], 500, files=[('bad.txt', 'text/plain', b'forbidden format')])
            if row_counts() != no_effect or state()['counts']['PUT'] != 0:
                raise AssertionError('Invalid input had observable side effects')
            created = []
            for role, board in zip(('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER'),
                                   ('COMMUNICATION', 'ADOPTION', 'PROTECTION')):
                files = [('empty.jpg', 'image/jpeg', b''), ('dog.jpg', 'image/jpeg', b'F3-image'),
                         ('dog.mp4', 'video/mp4', b'F3-video')] if role == 'ROLE_USER' else []
                if role == 'ROLE_USER':
                    with ThreadPoolExecutor(max_workers=1) as diagnostic:
                        pending = diagnostic.submit(post, tokens[role], change={'boardType': board}, files=files,
                            extra={'X-User-Id': '999999', 'X-User-Role': 'ROLE_FORGED'})
                        try:
                            response = pending.result(timeout=0.75)
                        except FutureTimeoutError:
                            with (EVIDENCE / 'f3-upload-threads.txt').open('w') as threads:
                                with contextlib.suppress(OSError, subprocess.TimeoutExpired):
                                    subprocess.run(['jcmd', str(app.pid), 'Thread.print'], env=base_env,
                                        stdout=threads, stderr=subprocess.STDOUT, timeout=5, check=False)
                            response = pending.result(timeout=10)
                else:
                    response = post(tokens[role], change={'boardType': board}, files=files,
                                    extra={'X-User-Id': '999999', 'X-User-Role': 'ROLE_FORGED'})
                data = response['data']
                if (response['code'] != 200 or response['message'] != '게시글이 생성되었습니다.'
                        or data['authorId'] != 42 or data['authorNickname'] != '합성작성자'
                        or data['boardType'] != board or set(data) != set(read['data'])):
                    raise AssertionError('POST identity, role, fields or envelope changed')
                reread = expect(base, '/api/v1/posts/read/' + str(data['postId']), 200)['data']
                for key in ('postId', 'authorId', 'authorNickname', 'title', 'content', 'boardType', 'imageUrls'):
                    if reread[key] != data[key]:
                        raise AssertionError('POST/F2 round trip differs: ' + key)
                created.append(data['postId'])
            after_success = state()
            if after_success['counts']['PUT'] != 2 or after_success['counts']['DELETE'] != 0:
                raise AssertionError('Empty file skip, upload count or no-delete policy changed')
            for mime, content in [('image/jpeg', b'F3-image'), ('video/mp4', b'F3-video')]:
                matching = [obj for obj in after_success['objects'] if obj['content_type'] == mime]
                if len(matching) != 1:
                    raise AssertionError('Expected one synthetic image and one video object')
                obj = matching[0]
                if (obj['content_type'] != mime or obj['sha256'] != hashlib.sha256(content).hexdigest()
                        or not obj['key'].startswith(prefix)):
                    raise AssertionError('Actual SDK PUT bytes/MIME/prefix changed')
            returned_urls = expect(base, '/api/v1/posts/read/' + str(created[0]), 200)['data']['imageUrls']
            successful_puts = [item for item in after_success['requests'] if item['method'] == 'PUT' and item['status'] == 200]
            if returned_urls != ['https://fixture.invalid/' + item['key'] for item in successful_puts]:
                raise AssertionError('Media URL ordering/public base changed')
            # Every created HTTP post must have its six-field event and Nori projection.
            for post_id in created:
                payload = json.loads(sql("SELECT payload FROM pawbridge_community.outbox_events WHERE aggregate_id='" + str(post_id) + "'"))
                if payload != {key: expect(base, '/api/v1/posts/read/' + str(post_id), 200)['data'][key]
                               for key in ('postId','authorId','title','content','boardType','imageUrls')}:
                    raise AssertionError('HTTP-created event payload changed')
                if sql("SELECT count(*) FROM pawbridge_community.post_search_documents d JOIN pawbridge_community.posts p USING(post_id) WHERE d.post_id=" + str(post_id)
                    + " AND d.title_hash=md5(p.title) AND d.content_hash=md5(p.content) AND d.analyzer_version='nori-9.12.3-v1'") != '1':
                    raise AssertionError('HTTP-created post is missing its search projection')
            stable = row_counts()
            post(tokens['ROLE_USER'], 400, {'title': 'unsupported 😀'})
            if row_counts() != stable:
                raise AssertionError('Named charset rejection committed business rows')
            # A confirmed DB rollback retains uploaded objects and never retries POST or deletes.
            sql("REVOKE INSERT ON pawbridge_community.outbox_events FROM " + WRITER)
            before_failure = state()
            try:
                post(tokens['ROLE_USER'], 500, files=[('rollback.jpg', 'image/jpeg', b'rollback-object')])
            finally:
                sql('GRANT INSERT ON pawbridge_community.outbox_events TO ' + WRITER)
            failed = state()
            if (row_counts() != stable or failed['counts']['PUT'] != before_failure['counts']['PUT'] + 1
                    or len(failed['objects']) != len(before_failure['objects']) + 1 or failed['counts']['DELETE'] != 0):
                raise AssertionError('DB rollback/object retention/no automatic POST retry contract changed')
            expect(endpoint, '/__f3__/fail-next-put', 200, 'POST',
                   dict(control_headers, **{'Content-Type': 'application/json'}), b'{"after":1,"count":1,"status":400}')
            partial_before = state()
            post(tokens['ROLE_USER'], 500, files=[('one.jpg','image/jpeg',b'one'), ('two.jpg','image/jpeg',b'two')])
            partial = state()
            if (row_counts() != stable or partial['counts']['PUT'] != partial_before['counts']['PUT'] + 2
                    or len(partial['objects']) != len(partial_before['objects']) + 1 or partial['counts']['DELETE'] != 0):
                raise AssertionError('Partial upload must retain its first object and commit no DB business rows')
            sql('REVOKE SELECT(user_id,nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                fallback = post(tokens['ROLE_USER'])
                if fallback['data']['authorNickname'] != '사용자42':
                    raise AssertionError('Nickname unavailable write fallback changed')
            finally:
                sql('GRANT SELECT(user_id,nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            if post(tokens['ROLE_USER'])['data']['authorNickname'] != '합성작성자':
                raise AssertionError('Next POST did not recover after failure')
            post(None, 401)  # The preceding authenticated request cannot populate this request.
            for missing in ('999999', '0', '-1'):
                expect(base, '/api/v1/posts/read/' + missing, 404)
            for invalid in ('abc', '9223372036854775808'):
                expect(base, '/api/v1/posts/read/' + invalid, 500)
            for method, path in [('GET','/error'),('GET','/api/v1/users/internal/42/nickname'),
                ('GET','/api/posts/read/42'),('HEAD','/api/v1/posts/read/42'),
                ('OPTIONS','/api/v1/posts/read/42'),('POST','/api/v1/posts/read/42')]:
                expect(base, path, 403, method)
            if sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
                raise AssertionError('Reader/writer transaction leaked after HTTP response')
            result['packaged_post_get_auth_upload_rollback_recovery'] = 'passed'
        except Exception:
            if app is not None and app.poll() is None:
                with (EVIDENCE / 'f3-failure-threads.txt').open('w') as threads:
                    subprocess.run(['jcmd', str(app.pid), 'Thread.print'], env=base_env,
                        stdout=threads, stderr=subprocess.STDOUT, timeout=5, check=False)
            raise
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = stop_owned_child(app, 'Packaged application')
            finally:
                result['stub_sigterm_exit'] = stop_owned_child(stub, 'S3 stub')
                (EVIDENCE / 'f3-packaged-result.txt').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    log_text = logs.read_text()
    if log_text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in log_text:
        raise AssertionError('Both pools and HTTP graceful termination evidence is missing')
    if sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('Reader/writer connections remain after graceful termination')
    print('Hosted synthetic F3 POST/GET, auth effects, SDK uploads, rollback/recovery and graceful shutdown passed')


def cleanup():
    f2.require_hosted()
    owner = f2.require_hosted()
    token_file = pathlib.Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    with contextlib.suppress(FileNotFoundError):
        token_file.unlink()
    f2.cleanup()


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke, 'cleanup': cleanup}[sys.argv[1]]()
