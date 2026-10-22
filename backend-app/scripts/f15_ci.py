#!/usr/bin/env python3
"""Hosted-only public home-video reads after the original F3-F14 staged regressions."""
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f14_ci as f14

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
MARKER = 'pawbridge-f15-ci-only-v1'
SOURCE = 'cb0082881391b3ba885293520c2a6024a15a2034'
DDL_SOURCE = 'community-service/src/migration/resources/db/postgresql/V9__home_video_curation.sql'
DDL_HASH = '5a8dfe19923ed7119d7c92d22dd9a4508a3b6febd9468004ac0cc3fe1822334e'
COMPATIBILITY_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlHomeVideoCompatibilityTest'
TASKS = (('homeVideoCompatibilityTest', 'UTC'), ('homeVideoCompatibilitySeoulTest', 'Asia/Seoul'))
COLUMNS = ('id', 'video_id', 'published', 'position', 'created_at', 'title', 'channel_title',
           'thumbnail_url', 'duration_seconds', 'available', 'checked_at')
FIELDS = {'id', 'videoId', 'published', 'position', 'createdAt', 'title', 'channelTitle',
          'thumbnailUrl', 'durationSeconds', 'available', 'checkedAt'}
IDS = tuple('f15b0000-0000-0000-0000-' + format(number, '012d') for number in range(1, 7))
SUCCESS = '요청이 성공적으로 처리되었습니다.'
UNAVAILABLE = '영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'


def verify_results():
    f2.require_hosted()
    counts = {}
    for task, zone in TASKS:
        path = ROOT / 'boot/build/test-results' / task / ('TEST-' + COMPATIBILITY_CLASS + '.xml')
        try:
            suite = ET.parse(path).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F15 compatibility report missing or malformed: ' + task) from None
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != COMPATIBILITY_CLASS
                or values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or len(cases) != values['tests']
                or any(case.attrib.get('classname') != COMPATIBILITY_CLASS for case in cases)):
            raise SystemExit('F15 compatibility report requires executed successful unskipped cases: ' + task)
        counts[zone] = values['tests']
    print('Verified separate F15 compatibility execution counts: ' + json.dumps(counts, sort_keys=True))
    return counts


def verified_ddl():
    f2.require_hosted()
    raw = subprocess.check_output(['git', 'show', SOURCE + ':' + DDL_SOURCE], cwd=ROOT.parent)
    if hashlib.sha256(raw).hexdigest() != DDL_HASH:
        raise SystemExit('F15 original migration checksum mismatch')
    ddl = (ROOT / 'scripts/f15_fixture.sql').read_text()
    if raw.decode('utf-8').strip() not in ddl:
        raise SystemExit('F15 fixture differs from the full pinned V9 board/initial-row/videos/index DDL')
    return ddl, 'source_sha=' + SOURCE + '\n' + DDL_HASH + '  ' + DDL_SOURCE + '\n'


def fixture_catalog(query, stage):
    """Only static F15 marker/shape/ACL/constraint queries get bounded diagnostics."""
    if stage not in ('marker', 'shape', 'acl', 'constraints', 'board') or not query.startswith('SELECT '):
        raise ValueError('F15 diagnostic is restricted to its static read-only fixture checks')
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError as failure:
        stderr = failure.stderr if isinstance(failure.stderr, str) else ''
        primary = next((line.strip() for line in stderr.splitlines()
                        if re.match(r'^\s*(?:ERROR|FATAL):', line)), '(no PostgreSQL primary error captured)')
        for key, value in os.environ.items():
            if value and any(label in key.upper() for label in ('PASSWORD', 'SECRET', 'TOKEN')):
                primary = primary.replace(value, '[redacted]')
        primary = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', primary)
        primary = ''.join(character for character in primary if character.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f15-fixture-catalog-error.txt').write_text(
            'stage=f15-home-' + stage + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F15 fixture query failed; see bounded f15-fixture-catalog-error.txt') from None


def require_fixture():
    owner = f14.require_fixture()
    if os.environ.get('F15_CI_OWNER') != owner:
        raise SystemExit('F15 fixture owner mismatch')
    if fixture_catalog('SELECT value || \':\' || owner FROM pawbridge_f15_fixture.marker', 'marker') != MARKER + ':' + owner:
        raise SystemExit('F15 fixture identity mismatch')
    if fixture_catalog("SELECT count(*)=13 AND count(*) FILTER (WHERE table_name='home_video_board')=2 "
            "AND bool_and(is_identity='NO') AND bool_and((table_name,column_name,data_type,"
            "COALESCE(character_maximum_length,0),is_nullable,COALESCE(datetime_precision,0),"
            "regexp_replace(COALESCE(column_default,''),'::bigint|[()[:space:]]','','g')) IN ("
            "('home_video_board','id','smallint',0,'NO',0,''),('home_video_board','revision','bigint',0,'NO',0,'0'),"
            "('home_videos','id','uuid',0,'NO',0,''),('home_videos','video_id','character varying',11,'NO',0,''),"
            "('home_videos','published','boolean',0,'NO',0,'false'),('home_videos','position','integer',0,'NO',0,''),"
            "('home_videos','created_at','timestamp with time zone',0,'NO',6,''),"
            "('home_videos','title','character varying',500,'YES',0,''),"
            "('home_videos','channel_title','character varying',300,'YES',0,''),"
            "('home_videos','thumbnail_url','character varying',2048,'YES',0,''),"
            "('home_videos','duration_seconds','bigint',0,'YES',0,''),"
            "('home_videos','available','boolean',0,'NO',0,'false'),"
            "('home_videos','checked_at','timestamp with time zone',0,'YES',6,''))) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' "
            "AND table_name IN ('home_videos','home_video_board')", 'shape') != 't':
        raise SystemExit('F15 fixture lacks exact board-two/videos-eleven types, defaults and nullability')
    if fixture_catalog("SELECT count(*)=13 AND bool_and("
            "has_column_privilege('pawbridge_f2_reader','pawbridge_community.'||table_name,column_name,'SELECT') "
            "= (table_name='home_videos') "
            "AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_community.'||table_name,column_name,'INSERT,UPDATE,REFERENCES') "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.'||table_name,column_name,'SELECT,INSERT,UPDATE,REFERENCES') "
            "AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_community.'||table_name,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.'||table_name,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) "
            "AND pg_get_serial_sequence('pawbridge_community.home_videos','id') IS NULL "
            "AND pg_get_serial_sequence('pawbridge_community.home_video_board','id') IS NULL "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' "
            "AND table_name IN ('home_videos','home_video_board')", 'acl') != 't':
        raise SystemExit('F15 fixture lacks exact video-reader-eleven/board-zero/writer-zero/no-sequence ACL')
    if fixture_catalog("SELECT (SELECT count(*)=2 FROM pg_constraint c WHERE contype='p' AND convalidated "
            "AND NOT condeferrable AND conrelid IN ('pawbridge_community.home_videos'::regclass,'pawbridge_community.home_video_board'::regclass) "
            "AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='id')]) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.home_videos'::regclass "
            "AND conname='home_videos_video_id_key' AND contype='u' AND convalidated AND NOT condeferrable "
            "AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_community.home_videos'::regclass AND attname='video_id')]) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.home_video_board'::regclass "
            "AND contype='c' AND convalidated AND regexp_replace(pg_get_expr(conbin,conrelid),'[()[:space:]]','','g')='id=1') "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.home_videos'::regclass "
            "AND contype='c' AND convalidated AND regexp_replace(pg_get_expr(conbin,conrelid),'::text|[()[:space:]]','','g')="
            "'video_id~''^[A-Za-z0-9_-]{11}$''') "
            "AND (SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am a ON a.oid=c.relam "
            "WHERE i.indrelid='pawbridge_community.home_videos'::regclass AND c.relname='idx_home_video_refresh' "
            "AND i.indisvalid AND i.indisready AND NOT i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL "
            "AND i.indnatts=1 AND a.amname='btree' AND i.indkey[0]=(SELECT attnum FROM pg_attribute "
            "WHERE attrelid='pawbridge_community.home_videos'::regclass AND attname='checked_at'))", 'constraints') != 't':
        raise SystemExit('F15 fixture lacks pinned validated PK/unique/CHECK/refresh-index constraints')
    if fixture_catalog('SELECT count(*)=1 AND bool_and(id=1 AND revision=0) FROM pawbridge_community.home_video_board', 'board') != 't':
        raise SystemExit('F15 fixture lost the sole original board revision-zero row')
    return owner


def database_snapshot(include_home=True):
    tables = [('users', 'pawbridge_user.users', 'user_id'), ('favorites', 'pawbridge_user.favorites', 'favorite_id'),
              ('posts', 'pawbridge_community.posts', 'post_id'), ('comments', 'pawbridge_community.comments', 'comment_id'),
              ('f4comments', 'pawbridge_f4_fixture.comments', 'comment_id'), ('notes', 'pawbridge_f4_fixture.private_notes', 'note_id'),
              ('outbox', 'pawbridge_community.outbox_events', 'outbox_id'), ('search', 'pawbridge_community.post_search_documents', 'post_id')]
    if include_home:
        tables += [('videos', 'pawbridge_community.home_videos', 'id'), ('board', 'pawbridge_community.home_video_board', 'id')]
    fields = ["'" + name + "',(SELECT COALESCE(json_agg(r ORDER BY " + key + "),'[]'::json) FROM " + table + ' r)'
              for name, table, key in tables]
    return json.loads(f3.sql('SELECT json_build_object(' + ','.join(fields) + ')'))


def prepare():
    f2.require_hosted()
    owner = f14.require_fixture()
    ddl, evidence = verified_ddl()
    f2.export_environment({'F15_CI_OWNER': owner})
    before = database_snapshot(include_home=False)
    f3.sql(input_text=ddl)
    if database_snapshot(include_home=False) != before:
        raise SystemExit('F15 fixture extension changed existing business rows')
    require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f15-ddl-sha256.txt').write_text(evidence)
    print('Added only pinned V9 home-video schema and exact video-column SELECT after F14')


def prepare_business_rows():
    require_fixture()
    if f3.sql('SELECT count(*) FROM pawbridge_community.home_videos') != '0':
        raise SystemExit('F15 JAR requires the PG test to have removed its owned video rows')
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=123456)
    fresh = (now - datetime.timedelta(days=1)).isoformat().replace('+00:00', 'Z')
    stale = (now - datetime.timedelta(days=31)).isoformat().replace('+00:00', 'Z')
    rows = []
    for index, identity in enumerate(IDS):
        rows.append({'id': identity, 'videoId': 'F15Video' + format(index + 1, '03d'), 'published': True,
                     'position': index if index < 4 else -index,
                     'createdAt': '2025-01-02T03:04:05.123456Z',
                     'title': None if index == 0 else 'F15_PRIVATE_TITLE_NOT_LOGGED',
                     'channelTitle': '' if index == 0 else 'F15_PRIVATE_CHANNEL_NOT_LOGGED',
                     'thumbnailUrl': None if index == 0 else 'https://fixture.invalid/F15_PRIVATE_METADATA',
                     'durationSeconds': None if index == 0 else 3000000000,
                     'available': index != 5, 'checkedAt': stale if index == 4 else fresh})
    values = []
    order = ('id', 'videoId', 'published', 'position', 'createdAt', 'title', 'channelTitle',
             'thumbnailUrl', 'durationSeconds', 'available', 'checkedAt')
    for row in rows:
        def literal(value):
            if value is None: return 'NULL'
            if isinstance(value, bool): return 'true' if value else 'false'
            if isinstance(value, int): return str(value)
            return f4.literal(value)
        values.append('(' + ','.join(literal(row[key]) for key in order) + ')')
    f3.sql('BEGIN; INSERT INTO pawbridge_community.home_videos(' + ','.join(COLUMNS) + ') VALUES '
           + ','.join(values) + '; COMMIT;')
    return rows[:3]


def remove_visible_rows():
    require_fixture()
    f3.sql('DELETE FROM pawbridge_community.home_videos WHERE id IN (' + ','.join(f4.literal(value) for value in IDS[:4]) + ')')


def cleanup_business_rows():
    require_fixture()
    f3.sql('DELETE FROM pawbridge_community.home_videos WHERE id IN (' + ','.join(f4.literal(value) for value in IDS) + ')')


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file(): raise SystemExit('Packaged JAR missing')
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'pool_maximum': 4,
              'home_get_checks': 0, 'nonempty_checks': 0, 'empty_checks': 0, 'unavailable_checks': 0,
              'malformed_bearer_checks': 0, 'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f15-packaged-startup.log', EVIDENCE / 'f15-s3-stub.log'
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], len(storage['requests'])

    def check(expected, malformed=False, status=200):
        headers = {'X-User-Id': '9999999', 'X-User-Role': 'ROLE_ADMIN'}
        if malformed: headers['Authorization'] = 'Bearer definitely-not-a-jwt'
        before = snapshot()
        response = f3.expect(base, '/api/v1/home/videos', status, headers=headers)
        if snapshot() != before:
            raise AssertionError('Home GET changed a business row or contacted S3')
        if response != {'code': status, 'message': SUCCESS if status == 200 else UNAVAILABLE,
                        'data': expected if status == 200 else None}:
            raise AssertionError('Home GET lost its exact video array, Instant/null fields or safe 503 envelope')
        if status == 200 and (not isinstance(response['data'], list)
                             or any(set(row) != FIELDS for row in response['data'])):
            raise AssertionError('Home GET must return exactly eleven fields per video')
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F15 request left a DB transaction active')
        result['home_get_checks'] += 1
        result['unavailable_checks' if status != 200 else 'nonempty_checks' if expected else 'empty_checks'] += 1
        if malformed: result['malformed_bearer_checks'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset, seeded = 0, False
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            expected = prepare_business_rows(); seeded = True
            check(expected); check(expected, malformed=True)
            f3.sql('REVOKE SELECT(checked_at) ON pawbridge_community.home_videos FROM pawbridge_f2_reader')
            try: check(None, status=503)
            finally: f3.sql('GRANT SELECT(checked_at) ON pawbridge_community.home_videos TO pawbridge_f2_reader')
            check(expected)
            remove_visible_rows(); check([])
            require_fixture()
        finally:
            try:
                if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F15 packaged application')
            finally:
                try:
                    if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F15 S3 stub')
                finally:
                    try:
                        if seeded: cleanup_business_rows()
                    finally:
                        (EVIDENCE / 'f15-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F15 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F15 normal HTTP/single-pool shutdown evidence missing')
    forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                 'F15_PRIVATE_TITLE_NOT_LOGGED', 'F15_PRIVATE_CHANNEL_NOT_LOGGED', 'F15_PRIVATE_METADATA')
    if any(value in text for value in forbidden):
        raise AssertionError('F15 application log exposed credentials or synthetic metadata')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F15 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f15-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F15 public video GET, filters, permission recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
