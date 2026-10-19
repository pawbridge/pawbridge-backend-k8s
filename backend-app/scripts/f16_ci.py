#!/usr/bin/env python3
"""Hosted-only visit totals after F3-F15; no visit counting or lifecycle jobs."""
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f15_ci as f15

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
MARKER = 'pawbridge-f16-ci-only-v1'
SOURCE = '33cad5567767f8730fc2c4389815ecd031f41dc0'
DDL_SOURCE = 'community-service/src/migration/resources/db/postgresql/V11__daily_visitors.sql'
DDL_HASH = 'd230ea0dc083bf0a3ab9fcf16ff06fe1591722d232ddc07c42de28f057daeacb'
PACKAGE = 'com.pawbridge.backend.compatibility.'
DATA_CLASS = PACKAGE + 'PostgresqlVisitTodayCompatibilityTest'
ACCESS_CLASS = PACKAGE + 'PostgresqlVisitTodayAccessTest'
MVC_CLASS = PACKAGE + 'F16LegacyVisitMvcTest'
REPORTS = {'visitTodayCompatibilityTest': (DATA_CLASS, ACCESS_CLASS),
           'visitTodayCompatibilitySeoulTest': (DATA_CLASS,), 'visitTodayLegacyMvcTest': (MVC_CLASS,)}
SUCCESS = '요청이 성공적으로 처리되었습니다.'
UNAVAILABLE = '방문 통계를 불러올 수 없습니다.'
DISABLED_EVIDENCE = 'JAR:404+unchanged snapshot; no-visit-beans/JDBC-mock-zero:Boot tests only'


def verify_results():
    f2.require_hosted()
    counts = {}
    for task, classes in REPORTS.items():
        directory = ROOT / 'boot/build/test-results' / task
        if {path.name for path in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in classes}:
            raise SystemExit('F16 compatibility task requires its exact XML class set: ' + task)
        counts[task] = {}
        for name in classes:
            try:
                suite = ET.parse(directory / ('TEST-' + name + '.xml')).getroot()
                values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
            except (OSError, ET.ParseError, KeyError, ValueError):
                raise SystemExit('F16 compatibility report missing or malformed: ' + task) from None
            cases = suite.findall('testcase')
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != name or values['tests'] <= 0
                    or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                    or len(cases) != values['tests'] or any(case.attrib.get('classname') != name for case in cases)
                    or any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped'))):
                raise SystemExit('F16 compatibility requires executed successful unskipped cases: ' + task)
            counts[task][name] = values['tests']
    print('Verified F16 UTC data/access, Seoul data and DB-free legacy MVC reports: ' + json.dumps(counts, sort_keys=True))
    return counts


def verified_ddl():
    f2.require_hosted()
    raw = subprocess.check_output(['git', 'show', SOURCE + ':' + DDL_SOURCE], cwd=ROOT.parent)
    if hashlib.sha256(raw).hexdigest() != DDL_HASH:
        raise SystemExit('F16 original migration checksum mismatch')
    original = ('CREATE TABLE pawbridge_community.daily_visit_count ('
                + raw.decode().split('CREATE TABLE pawbridge_community.daily_visit_count (', 1)[1]
                .split('CREATE TABLE pawbridge_community.visit_collection', 1)[0]).strip()
    ddl = (ROOT / 'scripts/f16_fixture.sql').read_text()
    if original not in ddl:
        raise SystemExit('F16 fixture differs from the pinned V11 count-table fragment')
    return ddl, 'source_sha=' + SOURCE + '\n' + DDL_HASH + '  ' + DDL_SOURCE + '\n'


def fixture_catalog(query, stage):
    """Only static read-only F16 fixture checks receive bounded catalog diagnostics."""
    if stage not in ('marker', 'shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F16 diagnostic is restricted to its static read-only fixture checks')
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
        (EVIDENCE / 'f16-fixture-catalog-error.txt').write_text(
            'stage=f16-visit-' + stage + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F16 fixture query failed; see bounded f16-fixture-catalog-error.txt') from None


def require_fixture():
    owner = f15.require_fixture()
    if os.environ.get('F16_CI_OWNER') != owner:
        raise SystemExit('F16 fixture owner mismatch')
    if fixture_catalog("SELECT value || ':' || owner FROM pawbridge_f16_fixture.marker", 'marker') != MARKER + ':' + owner:
        raise SystemExit('F16 fixture identity mismatch')
    if fixture_catalog("SELECT count(*)=2 AND bool_and(is_identity='NO' AND is_generated='NEVER' AND column_default IS NULL) "
            "AND bool_and((column_name,data_type,is_nullable) IN (('visit_date','date','NO'),('unique_visitors','bigint','NO'))) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='daily_visit_count'", 'shape') != 't':
        raise SystemExit('F16 fixture lacks the exact DATE/BIGINT NOT NULL count-table shape')
    if fixture_catalog("SELECT count(*)=2 AND bool_and("
            "has_column_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count',column_name,'SELECT') "
            "AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count',column_name,'INSERT,UPDATE,REFERENCES') "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.daily_visit_count',column_name,'SELECT,INSERT,UPDATE,REFERENCES')) "
            "AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.daily_visit_count','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND pg_get_serial_sequence('pawbridge_community.daily_visit_count','visit_date') IS NULL "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='daily_visit_count'", 'acl') != 't':
        raise SystemExit('F16 fixture lacks exact reader-two/writer-zero/no-sequence ACL')
    if fixture_catalog("SELECT (SELECT count(*)=2 FROM pg_constraint WHERE conrelid='pawbridge_community.daily_visit_count'::regclass) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.daily_visit_count'::regclass "
            "AND contype='p' AND convalidated AND NOT condeferrable AND conkey=ARRAY[(SELECT attnum FROM pg_attribute "
            "WHERE attrelid='pawbridge_community.daily_visit_count'::regclass AND attname='visit_date')]) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.daily_visit_count'::regclass "
            "AND contype='c' AND convalidated AND conkey=ARRAY[(SELECT attnum FROM pg_attribute "
            "WHERE attrelid='pawbridge_community.daily_visit_count'::regclass AND attname='unique_visitors')] "
            "AND regexp_replace(pg_get_expr(conbin,conrelid),'::bigint|[()[:space:]]','','g')='unique_visitors>=0')", 'constraints') != 't':
        raise SystemExit('F16 fixture lacks the exact validated date PK/nonnegative count CHECK')
    return owner


def database_snapshot():
    previous = f15.database_snapshot()
    previous['daily_visit_count'] = json.loads(f3.sql("SELECT COALESCE(json_agg(v ORDER BY visit_date),'[]'::json) "
                                                       'FROM pawbridge_community.daily_visit_count v'))
    return previous


def prepare():
    f2.require_hosted()
    owner = f15.require_fixture()
    ddl, evidence = verified_ddl()
    f2.export_environment({'F16_CI_OWNER': owner})
    before = f15.database_snapshot()
    f3.sql(input_text=ddl)
    if f15.database_snapshot() != before:
        raise SystemExit('F16 fixture extension changed existing business rows')
    require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f16-ddl-sha256.txt').write_text(evidence)
    print('Added only pinned V11 daily_visit_count and two-column SELECT after F15')


def seoul_day():
    return datetime.datetime.now(datetime.timezone.utc).astimezone(ZoneInfo('Asia/Seoul')).date()


def prepare_business_rows():
    require_fixture()
    if f3.sql('SELECT count(*) FROM pawbridge_community.daily_visit_count') != '0':
        raise SystemExit('F16 JAR requires the PG tests to have removed their owned visit rows')
    today = seoul_day()
    counts = {(today + datetime.timedelta(days=offset)).isoformat(): count
              for offset, count in ((-1, 7), (0, 9007199254740993), (1, 11))}
    values = ','.join('(DATE ' + f4.literal(day) + ',' + str(count) + ')' for day, count in counts.items())
    f3.sql('BEGIN; INSERT INTO pawbridge_community.daily_visit_count(visit_date,unique_visitors) VALUES ' + values + '; COMMIT;')
    return counts


def cleanup_business_rows(counts):
    require_fixture()
    for day, count in counts.items():
        if (not isinstance(day, str) or datetime.date.fromisoformat(day).isoformat() != day
                or type(count) is not int or count < 0 or count > 9223372036854775807):
            raise ValueError('F16 cleanup requires its exact owned date/count keys')
    if counts:
        keys = ','.join('(DATE ' + f4.literal(day) + ',' + str(count) + ')' for day, count in counts.items())
        f3.sql('DELETE FROM pawbridge_community.daily_visit_count WHERE (visit_date,unique_visitors) IN (' + keys + ')')


def require_day_count(data, counts, began, ended):
    """Compare the response to the owned day, allowing a request to cross Seoul midnight."""
    if (not isinstance(data, dict) or set(data) != {'date', 'count'} or not isinstance(data['date'], str)
            or type(data['count']) is not int or ended < began or (ended - began).days > 1):
        raise AssertionError('F16 response must contain its ISO date and exact long count')
    try:
        day = datetime.date.fromisoformat(data['date'])
    except ValueError:
        raise AssertionError('F16 response date is not ISO LocalDate') from None
    if (day.isoformat() != data['date'] or day not in (began, ended)
            or data['count'] != counts.get(data['date'], 0)):
        raise AssertionError('F16 response lost the initial Seoul day/count across the request window')


def _packaged_phase(jar, owner, enabled, result):
    label = 'enabled' if enabled else 'disabled'
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'], F2_DB_USER=os.environ['F2_TEST_DB_USER'],
        F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'], F2_POOL_MAX_SIZE='1', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        F3_STORAGE_BUCKET=bucket, F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', F3_STORAGE_REGION='us-east-1',
        DAILY_VISITORS_ENABLED='true' if enabled else 'false')
    app_path, stub_path = EVIDENCE / ('f16-' + label + '-startup.log'), EVIDENCE / ('f16-' + label + '-s3-stub.log')
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(counts, malformed=False, status=200):
        headers = {'X-User-Id': '9999999', 'X-User-Role': 'ROLE_ADMIN', 'Origin': 'https://foreign.invalid',
                   'Cookie': '__Host-pb_visit=forged', 'User-Agent': 'synthetic-bot'}
        if malformed: headers['Authorization'] = 'Bearer definitely-not-a-jwt'
        before = snapshot(); began = seoul_day()
        actual, raw, received = f3.request(base, '/api/v1/visits/today?date=1900-01-01&count=1', headers=headers)
        ended = seoul_day()
        if snapshot() != before:
            raise AssertionError('Visit GET changed a business row or contacted S3')
        if actual != status or received.get('Set-Cookie'):
            raise AssertionError('Visit GET lost its exact status or created a cookie')
        if enabled:
            if received.get('Cache-Control') != 'no-store':
                raise AssertionError('Visit 200/DAO503 must explicitly carry Cache-Control:no-store')
            response = json.loads(raw)
            if (not isinstance(response, dict) or set(response) != {'code', 'message', 'data'}
                    or response['code'] != status or response['message'] != (SUCCESS if status == 200 else UNAVAILABLE)):
                raise AssertionError('Visit GET lost its exact success or DAO503 envelope')
            if status == 200:
                require_day_count(response['data'], counts, began, ended)
                result['positive_checks' if response['data']['count'] else 'zero_checks'] += 1
            elif response['data'] is not None:
                raise AssertionError('Visit DAO503 must not expose error details')
            else: result['unavailable_checks'] += 1
        else:
            # The body/cache policy of the unmapped route is deliberately not a legacy oracle claim.
            result['disabled_404_checks'] += 1
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F16 request left a DB transaction active')
        result['visit_get_checks'] += 1
        if malformed: result['malformed_bearer_checks'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset, counts = 0, {}
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            if database_snapshot() != baseline:
                raise AssertionError('F16 startup changed business rows')
            storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
            if any(storage['counts'].values()) or storage['requests'] or storage['objects']:
                raise AssertionError('F16 startup contacted S3')
            if enabled:
                counts = prepare_business_rows()
                check(counts); check(counts, malformed=True)
                f3.sql('REVOKE SELECT(unique_visitors) ON pawbridge_community.daily_visit_count FROM pawbridge_f2_reader')
                try: check(counts, status=503)
                finally: f3.sql('GRANT SELECT(unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_f2_reader')
                check(counts)
                cleanup_business_rows(counts); check({})
            else: check({}, status=404)
            require_fixture()
        finally:
            try:
                if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F16 ' + label + ' application')
            finally:
                try:
                    if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F16 ' + label + ' S3 stub')
                finally:
                    if counts: cleanup_business_rows(counts)
    if database_snapshot() != baseline:
        raise AssertionError('F16 packaged phase did not restore its owned rows')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F16 exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F16 normal HTTP/two-pool shutdown evidence missing')
    if any(value in text for value in (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'])):
        raise AssertionError('F16 application log exposed credentials')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F16 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file(): raise SystemExit('Packaged JAR missing')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'reader_pool_maximum': 1, 'writer_pool_maximum': 1,
              'database_changes': 0, 's3_requests': 0, 'disabled_evidence': DISABLED_EVIDENCE}
    try:
        for label, enabled in (('enabled', True), ('disabled', False)):
            result[label] = {'status': 'incomplete', 'visit_get_checks': 0, 'positive_checks': 0, 'zero_checks': 0,
                             'unavailable_checks': 0, 'malformed_bearer_checks': 0, 'disabled_404_checks': 0}
            _packaged_phase(jar, owner, enabled, result[label])
        result['status'] = 'passed'
    finally:
        (EVIDENCE / 'f16-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F16 enabled totals/DAO recovery and one disabled404 JAR phase passed; SQL-zero proof stays in Boot tests')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
