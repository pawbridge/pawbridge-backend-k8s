#!/usr/bin/env python3
"""GitHub-hosted, disposable-only PG setup and bounded packaged HTTP verification."""
import contextlib
import json
import os
import pathlib
import re
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

IMAGE = 'postgres:17.11-bookworm@sha256:66aafa11cf15800a3c94763f7e11d1e7b2e37e5e84bc4ce027cb6e1e4bfaf4df'
DATABASE = 'pawbridge_f2_ci'
MARKER = 'pawbridge-f2-ci-only-v1'
OWNER = 'pawbridge.f2.ci-owner'
SCRIPT = pathlib.Path(__file__).resolve()
ROOT = SCRIPT.parent.parent
EVIDENCE = ROOT / 'build/ci'


def require_hosted():
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or os.environ.get('RUNNER_OS') != 'Linux'
            or os.environ.get('GITHUB_REPOSITORY') != 'pawbridge/pawbridge-backend-k8s'):
        raise SystemExit('F2 execution is restricted to this repository on GitHub-hosted Linux')
    run = os.environ.get('GITHUB_RUN_ID', '') + '-' + os.environ.get('GITHUB_RUN_ATTEMPT', '')
    if not re.fullmatch(r'[0-9]+-[0-9]+', run):
        raise SystemExit('Missing CI owner identity')
    return run


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=30, **kwargs)


def inspect_owned(name):
    data = json.loads(command(['docker', 'inspect', name], capture_output=True).stdout)[0]
    if data['Config'].get('Labels', {}).get(OWNER) != require_hosted():
        raise SystemExit('Refusing an unowned CI container')
    if data['HostConfig']['Privileged'] or data['HostConfig']['NetworkMode'] == 'host':
        raise SystemExit('Unexpected CI container privileges')
    return data


def sql(query, *, input_text=None):
    name = os.environ['F2_CI_CONTAINER']
    inspect_owned(name)
    env = dict(os.environ, PGPASSWORD=os.environ['F2_TEST_ADMIN_PASSWORD'])
    args = ['docker', 'exec', '--env', 'PGPASSWORD']
    if input_text is not None:
        args += ['--interactive', '--env', 'F2_TEST_DB_PASSWORD']
    args += [name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', DATABASE, '-At']
    if input_text is None:
        args += ['-c', query]
    return command(args, env=env, input=input_text, capture_output=True).stdout.strip()


def require_fixture():
    url = os.environ.get('F2_TEST_JDBC_URL', '')
    if not re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/pawbridge_f2_ci', url):
        raise SystemExit('Refusing non-loopback, non-fixture test URL')
    if os.environ.get('F2_TEST_DB_USER') != 'pawbridge_f2_reader':
        raise SystemExit('Unexpected fixture reader')
    result = sql("SELECT current_database() || ':' || current_user || ':' || value FROM pawbridge_f2_fixture.marker")
    if result != DATABASE + ':postgres:' + MARKER:
        raise SystemExit('Fixture identity mismatch')


def export_environment(values):
    with open(os.environ['GITHUB_ENV'], 'a') as target:
        for key, value in values.items():
            if '\n' in value or '\r' in value:
                raise SystemExit('Invalid CI environment value')
            target.write(key + '=' + value + '\n')
    os.environ.update(values)


def prepare():
    run = require_hosted()
    name = 'pawbridge-f2-ci-' + run
    existing = command(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'], capture_output=True).stdout.strip()
    if existing:
        raise SystemExit('Dedicated CI container already exists; not reusing it')
    admin_password = secrets.token_urlsafe(24)
    reader_password = secrets.token_urlsafe(24)
    print('::add-mask::' + admin_password)
    print('::add-mask::' + reader_password)
    env = dict(os.environ, POSTGRES_PASSWORD=admin_password)
    command(['docker', 'run', '--detach', '--name', name, '--label', OWNER + '=' + run,
             '--cpus', '2', '--memory', '512m', '--shm-size', '128m',
             '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,noexec,size=512m',
             '--publish', '127.0.0.1::5432', '--env', 'POSTGRES_PASSWORD',
             '--env', 'POSTGRES_DB=' + DATABASE, IMAGE], env=env, capture_output=True)
    # Export owner immediately so the always-run cleanup can also handle a partial setup.
    export_environment({'F2_CI_CONTAINER': name, 'F2_TEST_ADMIN_PASSWORD': admin_password,
                        'F2_TEST_ADMIN_USER': 'postgres', 'F2_TEST_DB_USER': 'pawbridge_f2_reader',
                        'F2_TEST_DB_PASSWORD': reader_password})
    info = inspect_owned(name)
    port = info['NetworkSettings']['Ports']['5432/tcp'][0]
    if port['HostIp'] != '127.0.0.1':
        raise SystemExit('Unexpected database bind address')
    export_environment({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:' + port['HostPort'] + '/' + DATABASE})
    deadline = time.monotonic() + 45
    while True:
        ready = subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-p', '5432', '-U', 'postgres', '-d', DATABASE],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        if ready.returncode == 0:
            break
        if time.monotonic() >= deadline:
            raise SystemExit('Fixture PostgreSQL readiness deadline exceeded')
        time.sleep(0.5)
    sql('', input_text=(SCRIPT.parent / 'f2_fixture.sql').read_text())
    require_fixture()
    version = sql("SELECT current_setting('server_version')")
    if sql("SELECT current_setting('server_version_num')") != '170011':
        raise SystemExit('Unexpected fixture PostgreSQL version')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'postgres-runtime.txt').write_text('image=' + IMAGE + '\nversion=' + version
        + '\narchitecture=linux/amd64\ncontainer=' + name + '\nfixture_marker=' + MARKER + '\n')
    print('Prepared isolated PostgreSQL 17.11 with SELECT-only application role')


def http_request(base, path, method='GET', headers=None):
    request = urllib.request.Request(base + path, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=6) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers


def expect(base, path, status, method='GET'):
    actual, body, headers = http_request(base, path, method, {'Authorization': 'Bearer invalid'})
    if actual != status:
        raise AssertionError('Unexpected HTTP status for ' + method + ' ' + path + ': ' + str(actual))
    if headers.get('Set-Cookie'):
        raise AssertionError('Read candidate must not establish a session')
    return json.loads(body) if body else None


def smoke():
    require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    sql("TRUNCATE pawbridge_community.posts,pawbridge_user.users; "
        "INSERT INTO pawbridge_user.users VALUES(42,'합성작성자'); "
        "INSERT INTO pawbridge_community.posts VALUES(42,42,'합성제목','합성내용','REPORT',"
        "'[\"https://fixture.invalid/가.jpg\",null,\"https://fixture.invalid/가.jpg\"]',NULL,"
        "'2026-10-17 15:01:02.123456','2026-10-17 16:02:00'); "
        "GRANT SELECT(user_id,nickname) ON pawbridge_user.users TO pawbridge_f2_reader")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    clean = {k: v for k, v in os.environ.items() if not k.startswith('F2_')}
    missing = subprocess.run(['java', '-jar', str(jar), '--spring.main.banner-mode=off'],
                             env=clean, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, text=True)
    if missing.returncode == 0 or 'Missing or invalid explicit setting: F2_JDBC_URL' not in missing.stdout:
        raise AssertionError('Unconfigured JAR did not fail before resource startup')
    (EVIDENCE / 'unconfigured-startup.log').write_text(missing.stdout)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    env = dict(clean, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'], F2_DB_USER=os.environ['F2_TEST_DB_USER'],
               F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'], F2_POOL_MAX_SIZE='4', F2_PORT=str(port))
    base = 'http://127.0.0.1:' + str(port)
    log_path = EVIDENCE / 'packaged-startup.log'
    result = {'unconfigured_exit': missing.returncode, 'timezone': 'Asia/Seoul', 'pool_maximum': 4}
    with log_path.open('w') as log:
        process = subprocess.Popen(['java', '-Duser.timezone=Asia/Seoul', '-Xmx256m', '-jar', str(jar),
                                    '--spring.main.banner-mode=off'], env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise AssertionError('Packaged application exited before readiness')
                try:
                    status, _, _ = http_request(base, '/api/v1/posts/read/42')
                    if status == 200:
                        break
                except (urllib.error.URLError, TimeoutError):
                    pass
                if time.monotonic() >= deadline:
                    raise AssertionError('Packaged HTTP readiness deadline exceeded')
                time.sleep(0.25)
            normal = expect(base, '/api/v1/posts/read/42', 200)
            data = normal['data']
            if normal['code'] != 200 or normal['message'] != '요청이 성공적으로 처리되었습니다.':
                raise AssertionError('Successful envelope changed')
            if set(data) != {'postId','authorId','authorNickname','title','content','boardType','imageUrls','createdAt','updatedAt'}:
                raise AssertionError('Post JSON fields changed')
            if data['authorNickname'] != '합성작성자' or data['boardType'] != 'REPORT':
                raise AssertionError('Wrong schema or nickname')
            if data['imageUrls'] != ['https://fixture.invalid/가.jpg', None, 'https://fixture.invalid/가.jpg']:
                raise AssertionError('Image array semantics changed')
            if data['createdAt'] != '2026-10-18T00:01:02.123456' or data['updatedAt'] != '2026-10-18T01:02:00':
                raise AssertionError('Legacy Seoul timestamp semantics changed')
            for path in ['/api/v1/posts/read/999999','/api/v1/posts/read/0','/api/v1/posts/read/-1']:
                absent = expect(base, path, 404)
                if absent['message'] != '게시글을 찾을 수 없습니다.' or absent['data'] is not None:
                    raise AssertionError('Missing post contract changed')
            expect(base, '/api/v1/posts/read/abc', 500)
            expect(base, '/api/v1/posts/read/9223372036854775808', 500)
            for method, path in [('GET','/error'),('HEAD','/api/v1/posts/read/42'),
                                 ('OPTIONS','/api/v1/posts/read/42'),('POST','/api/v1/posts/read/42')]:
                expect(base, path, 403, method)
            # Gateway-moved behavior: service-to-service user paths are refused with 404, and the temporary legacy
            # alias is rewritten to the v1 public read (same body), no longer denied.
            for internal in ('/api/v1/users/internal/42/nickname', '/api/users/internal/42/nickname'):
                if expect(base, internal, 404) != {'code': 404, 'message': '\uc720\ud6a8\ud558\uc9c0 \uc54a\uc740 \uc5d4\ub4dc\ud3ec\uc778\ud2b8\uc785\ub2c8\ub2e4.', 'data': None}:
                    raise AssertionError('Internal user path lost its fixed 404 body')
            if expect(base, '/api/posts/read/42', 200) != expect(base, '/api/v1/posts/read/42', 200):
                raise AssertionError('Legacy post read alias differs from the v1 route')
            sql('REVOKE SELECT(user_id,nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            fallback = expect(base, '/api/v1/posts/read/42', 200)
            if fallback['data']['authorNickname'] != '사용자42':
                raise AssertionError('Nickname failure did not preserve the post response')
            sql('GRANT SELECT(user_id,nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            if expect(base, '/api/v1/posts/read/42', 200)['data']['authorNickname'] != '합성작성자':
                raise AssertionError('Same pool did not recover after nickname failure')
            idle = sql("SELECT count(*) FROM pg_stat_activity WHERE usename='pawbridge_f2_reader' AND (state='idle in transaction' OR xact_start IS NOT NULL)")
            if idle != '0':
                raise AssertionError('Application transaction leaked after response')
            result['http_and_recovery'] = 'passed'
        finally:
            with contextlib.suppress(Exception):
                sql('GRANT SELECT(user_id,nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            process.send_signal(signal.SIGTERM) if process.poll() is None else None
            try:
                exit_code = process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
                raise AssertionError('Normal shutdown deadline exceeded; CI process was forced to stop')
            result['sigterm_exit'] = exit_code
            (EVIDENCE / 'packaged-startup-result.txt').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    text = log_path.read_text()
    if exit_code not in (0, 143) or 'Shutdown completed.' not in text or 'Graceful shutdown complete' not in text:
        raise AssertionError('Packaged application normal shutdown evidence missing')
    if sql("SELECT count(*) FROM pg_stat_activity WHERE usename='pawbridge_f2_reader'") != '0':
        raise AssertionError('Application pool did not close')
    print('Packaged HTTP, permission failure/recovery and normal shutdown passed')


def cleanup():
    name = os.environ.get('F2_CI_CONTAINER')
    if not name:
        return
    inspect_owned(name)
    command(['docker', 'stop', '--time', '15', name], capture_output=True)
    command(['docker', 'rm', name], capture_output=True)
    print('Removed only the owned, disposable CI container')


if __name__ == '__main__':
    require_hosted()
    {'prepare': prepare, 'smoke': smoke, 'cleanup': cleanup}[sys.argv[1]]()
