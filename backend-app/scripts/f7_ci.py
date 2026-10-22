#!/usr/bin/env python3
"""Hosted-only search checks on the existing owned F2-F6 PostgreSQL/S3 fixture."""
import datetime
import json
import os
import pathlib
import sys
import urllib.parse

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f6_ci as f6

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
SEARCH_COLUMNS = 'post_id,title_hash,content_hash,analyzer_version,tokens'


def require_fixture():
    return f6.require_fixture()


def prepare():
    require_fixture()
    f3.sql(input_text=(ROOT / 'scripts/f7_fixture.sql').read_text())
    if f3.sql("SELECT count(*)=5 AND bool_and(has_column_privilege('pawbridge_f2_reader',"
            "'pawbridge_community.post_search_documents',column_name,'SELECT')) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' "
            "AND table_name='post_search_documents' AND column_name IN "
            "('post_id','title_hash','content_hash','analyzer_version','tokens')") != 't':
        raise SystemExit('F7 fixture lacks the exact search reader columns')
    print('Granted only the five existing search columns to the same synthetic reader')


def prepare_business_rows():
    require_fixture()
    # Retain existing F4 comment/note FK rows and all prior smoke history.
    f3.sql('UPDATE pawbridge_community.posts SET deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP); '
           "INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42,'검색 합성작성자') "
           'ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname')


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    for value in tokens.values():
        print('::add-mask::' + value)
    bucket = 'f3-' + owner
    prefix = bucket + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR')
                if key in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'],
        PAWBRIDGE_DB_USER=f3.APP, PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], PAWBRIDGE_STORAGE_BUCKET=bucket,
        PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
        PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul',
              'pool_maximum': 4,
              'search_get_checks': 0, 'search_get_database_changes': 0, 'search_get_s3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f7-packaged-startup.log', EVIDENCE / 'f7-s3-stub.log'
    base = endpoint = ''

    def snapshot():
        database = f3.sql("SELECT json_build_object("
            "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
            "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
            "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents s))")
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database, storage['counts'], len(storage['requests'])

    def returned():
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND "
                "(state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('Search response left a DB transaction active')

    def search(keyword, expected=200, extra='', headers=None):
        path = '/api/v1/posts/search?' + urllib.parse.urlencode({'keyword': keyword}) + extra
        before = snapshot()
        response = f3.expect(base, path, expected, headers=headers)
        if snapshot() != before:
            raise AssertionError('Search GET changed posts/outbox/projection or contacted S3')
        result['search_get_checks'] += 1
        returned()
        if expected == 200:
            if (response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.'
                    or not isinstance(response['data'], list) or len(response['data']) > 10):
                raise AssertionError('Search envelope/array/Top10 contract changed')
            for item in response['data']:
                if set(item) != f6.FIELDS:
                    raise AssertionError('Search item must retain exactly the nine public fields')
                stored = json.loads(f3.sql('SELECT json_build_object(\'createdAt\',created_at,\'updatedAt\',updated_at) '
                    'FROM pawbridge_community.posts WHERE post_id=' + str(item['postId'])))
                for field in ('createdAt', 'updatedAt'):
                    if datetime.datetime.fromisoformat(item[field]) != datetime.datetime.fromisoformat(stored[field]):
                        raise AssertionError('Search must preserve direct JDBC LocalDateTime extraction')
            return response['data']
        expected_message = {503: '검색 서비스를 사용할 수 없습니다.', 500: '서버 에러!'}[expected]
        if response != {'code': expected, 'message': expected_message, 'data': None}:
            raise AssertionError('Search failure did not return the safe whole-response envelope')
        return response

    def create(title, files=()):
        body, mime = f3.multipart({'title': title, 'content': 'F7 합성 본문', 'boardType': 'COMMUNICATION'}, files)
        return f3.expect(base, '/api/v1/posts', 200, 'POST',
            {'Authorization': 'Bearer ' + tokens['ROLE_USER'], 'Content-Type': mime}, body)['data']

    def remove(post_id):
        f3.expect(base, '/api/v1/posts/' + str(post_id), 200, 'DELETE',
            {'Authorization': 'Bearer ' + tokens['ROLE_USER']})

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            if search('강아지') != []:
                raise AssertionError('No active rows must produce an empty search array')
            dog = create('강아지', [('search.jpg', 'image/jpeg', b'F7-search-media')])
            cat = create('고양이')
            rows = search('강아지')
            if [row['postId'] for row in rows] != [dog['postId']]:
                raise AssertionError('POST projection was not immediately searchable')
            detail = f3.expect(base, '/api/v1/posts/read/' + str(dog['postId']), 200)['data']
            for field in f6.FIELDS - {'createdAt', 'updatedAt'}:
                if rows[0][field] != detail[field]:
                    raise AssertionError('Search and single GET differ outside their documented date extraction')
            both = search('강아지 고양이')
            if {row['postId'] for row in both} != {dog['postId'], cat['postId']}:
                raise AssertionError('Analyzed OR search did not include both terms')
            ignored = search('강아지 고양이', extra='&page=999&size=1&boardType=REPORT',
                headers={'X-User-Id': '999', 'X-User-Role': 'ROLE_ADMIN', 'Authorization': 'Bearer invalid'})
            if ignored != both:
                raise AssertionError('Irrelevant parameters or caller identity changed public search')

            # Even a stale NON-matching cat blocks the dog query; GET must not repair it.
            f3.sql("UPDATE pawbridge_community.post_search_documents SET title_hash='stale' WHERE post_id=" + str(cat['postId']))
            search('강아지', 503)
            f3.sql('UPDATE pawbridge_community.post_search_documents d SET title_hash=md5(p.title) '
                'FROM pawbridge_community.posts p WHERE d.post_id=p.post_id AND p.post_id=' + str(cat['postId']))
            if len(search('강아지')) != 1:
                raise AssertionError('Search did not recover after projection repair')
            f3.sql('REVOKE SELECT(tokens) ON pawbridge_community.post_search_documents FROM pawbridge_f2_reader')
            try:
                search('강아지', 503)
            finally:
                f3.sql('GRANT SELECT(tokens) ON pawbridge_community.post_search_documents TO pawbridge_f2_reader')
            if len(search('강아지')) != 1:
                raise AssertionError('Search did not recover after restoring SELECT')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                if any(row['authorNickname'] != '사용자42' for row in search('강아지 고양이')):
                    raise AssertionError('Defined User failure did not retain item-level fallback')
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            if any(row['authorNickname'] != '검색 합성작성자' for row in search('강아지 고양이')):
                raise AssertionError('Search nickname lookup did not recover')
            saved = f3.sql('SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id=' + str(dog['postId']))
            f3.sql("UPDATE pawbridge_community.posts SET image_urls='{\"invalid\":true}'::json WHERE post_id=" + str(dog['postId']))
            try:
                search('강아지', 500)
            finally:
                f3.sql('UPDATE pawbridge_community.posts SET image_urls=' + f4.literal(saved)
                    + '::json WHERE post_id=' + str(dog['postId']))
            body, mime = f3.multipart({'title': '고양이'})
            f3.expect(base, '/api/v1/posts/' + str(dog['postId']), 200, 'PUT',
                {'Authorization': 'Bearer ' + tokens['ROLE_USER'], 'Content-Type': mime}, body)
            if search('강아지') != []:
                raise AssertionError('PUT left the old term searchable')
            if {row['postId'] for row in search('고양이')} != {dog['postId'], cat['postId']}:
                raise AssertionError('PUT did not refresh the searchable term')
            remove(dog['postId'])
            if [row['postId'] for row in search('고양이')] != [cat['postId']]:
                raise AssertionError('DELETE remained searchable')
            f3.expect(base, '/api/v1/posts/read/' + str(dog['postId']), 404)
            remove(cat['postId'])
            if search('고양이') != []:
                raise AssertionError('Last DELETE must leave an empty search result')
            result['post_search_put_delete_stale_permissions_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F7 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F7 S3 stub')
                (EVIDENCE / 'f7-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F7 normal HTTP/single-pool shutdown evidence missing')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F7 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f7-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F7 actual-JAR search journey, zero GET writes/S3, recovery and normal shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
