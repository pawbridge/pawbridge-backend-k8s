#!/usr/bin/env python3
"""Hosted-only F5 PUT checks; shares the existing owned PG, SDK stub and bounded startup helper."""
import hashlib
import json
import os
import pathlib
import sys
import uuid
import f2_ci as f2
import f3_ci as f3
import f4_ci as f4

ROOT=f3.ROOT
EVIDENCE=f3.EVIDENCE
MARKER='pawbridge-f5-ci-only-v1'


def require_fixture():
    owner=f4.require_fixture()
    if os.environ.get('F5_CI_OWNER')!=owner:
        raise SystemExit('F5 fixture owner mismatch')
    if f3.sql("SELECT value || ':' || owner FROM pawbridge_f5_fixture.marker")!=MARKER+':'+owner:
        raise SystemExit('F5 fixture identity mismatch')
    return owner


def prepare():
    owner=f4.require_fixture()
    f2.export_environment({'F5_CI_OWNER':owner})
    f3.sql(input_text=(ROOT/'scripts/f5_fixture.sql').read_text())
    require_fixture()
    print('Added only title/content/image update grants to the same synthetic writer')


def smoke():
    owner=require_fixture()
    jar=ROOT/'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():raise SystemExit('Packaged JAR missing')
    tokens=json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    for value in tokens.values():print('::add-mask::'+value)
    bucket='f3-'+owner;prefix=bucket+'/'
    controls={'X-F3-Owner':owner}
    base_env={k:os.environ[k] for k in ('PATH','JAVA_HOME','LANG','LC_ALL','HOME','TMPDIR') if k in os.environ}
    app_env=dict(base_env,PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'],PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket,PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid',PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access',PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',PAWBRIDGE_STORAGE_REGION='us-east-1')
    result={'status':'incomplete','timezone':'Asia/Seoul','pool_maximum': 4}
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    app_path=EVIDENCE/'f5-packaged-startup.log';stub_path=EVIDENCE/'f5-s3-stub.log'
    endpoint=base=''
    state=lambda:f3.expect(endpoint,'/__f3__/state',200,headers=controls)
    def row(post_id):return f3.sql('SELECT to_jsonb(p)::text FROM pawbridge_community.posts p WHERE post_id='+str(post_id))
    def events(post_id):return json.loads(f3.sql("SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e WHERE type='POST_UPDATED' AND aggregate_id="+f4.literal(post_id)))
    def create(files=()):
        body,mime=f3.multipart({'title':'F5 합성 제목','content':'F5 합성 본문','boardType':'COMMUNICATION'},files)
        return f3.expect(base,'/api/v1/posts',200,'POST',{'Authorization':'Bearer '+tokens['ROLE_USER'],'Content-Type':mime},body)['data']
    def put(post_id,fields=None,files=(),expected=200,token='ROLE_USER'):
        body,mime=f3.multipart(fields or {},files)
        headers={'Content-Type':mime,'X-User-Id':'999999','X-User-Role':'ROLE_ADMIN'}
        if token is not None:headers['Authorization']='Bearer '+tokens[token]
        response=f3.expect(base,'/api/v1/posts/'+str(post_id),expected,'PUT',headers,body)
        if response['code']!=expected:raise AssertionError('PUT envelope code changed')
        if expected==200 and (response['message']!='게시글이 수정되었습니다.' or len(response['data'])!=9):raise AssertionError('PUT response fields changed')
        return response
    def read(post_id):return f3.expect(base,'/api/v1/posts/read/'+str(post_id),200)['data']
    with app_path.open('w') as app_log,stub_path.open('w') as stub_log:
        app=stub=None;app_offset=0
        try:
            app,stub,base,endpoint,attempts,app_offset=f4.start_ready_children(jar,app_env,base_env,owner,bucket,prefix,stub_log,app_log,stub_path,app_path)
            result['startup_attempts']=attempts
            first=create([('old.jpg','image/jpeg',b'F5-old')]);post_id=first['postId'];before=read(post_id)
            stable=row(post_id),state()['counts']
            put(post_id,expected=401,token=None);put(post_id,expected=401,token='refresh')
            if (row(post_id),state()['counts'])!=stable:raise AssertionError('Unauthenticated PUT had side effects')
            foreign=create();f3.sql('UPDATE pawbridge_community.posts SET author_id=43 WHERE post_id='+str(foreign['postId']))
            stable_foreign=row(foreign['postId']),state()['counts']
            put(foreign['postId'],expected=403,token='ROLE_ADMIN')
            if (row(foreign['postId']),state()['counts'])!=stable_foreign:raise AssertionError('Foreign ADMIN had side effects')
            put(99999999,expected=404)
            storage_before=state();response=put(post_id,{'title':'수정된 제목','content':''},[('new.mp4','video/mp4',b'F5-new-video')])['data']
            current=read(post_id)
            if response['authorId']!=42 or response['title']!='수정된 제목' or response['content']!='' or response['updatedAt']!=before['updatedAt']:
                raise AssertionError('PUT values, principal or response-before-flush timestamp changed')
            for key in ('postId','authorId','authorNickname','title','content','boardType','imageUrls','createdAt'):
                if response[key]!=current[key]:raise AssertionError('PUT/GET contract differs: '+key)
            changed=state()['requests'][len(storage_before['requests']):]
            if [x['method'] for x in changed]!=['DELETE','PUT'] or changed[0]['key']!=first['imageUrls'][0].removeprefix('https://fixture.invalid/'):
                raise AssertionError('SDK replacement order changed')
            new_objects=[x for x in state()['objects'] if x['key']==response['imageUrls'][0].removeprefix('https://fixture.invalid/')]
            if len(new_objects)!=1 or new_objects[0]['sha256']!=hashlib.sha256(b'F5-new-video').hexdigest() or new_objects[0]['content_type']!='video/mp4':
                raise AssertionError('Actual SDK replacement bytes or MIME changed')
            # Explicit difference from the legacy service: no POST_UPDATED outbox row is written by the candidate.
            if events(post_id):raise AssertionError('Candidate must not write a POST_UPDATED outbox row')
            unchanged=row(post_id);put(post_id)
            if row(post_id)!=unchanged or events(post_id):raise AssertionError('No-op PUT row timestamp/outbox contract changed')
            count_before=state()['counts'];empty=put(post_id,files=[('empty.jpg','image/jpeg',b'')])['data']
            if empty['imageUrls'] or state()['counts']['PUT']!=count_before['PUT'] or state()['counts']['DELETE']!=count_before['DELETE']+1:
                raise AssertionError('One empty multipart file did not replace with an empty list')
            # Failed DELETE is best effort and must not suppress the later SDK upload.
            item=create([('old.jpg','image/jpeg',b'best-effort-old')]);before_storage=state()
            f3.expect(endpoint,'/__f3__/fail-next-delete',200,'POST',dict(controls,**{'Content-Type':'application/json'}),json.dumps({'after':0,'count':1,'status':400}).encode())
            put(item['postId'],files=[('new.jpg','image/jpeg',b'best-effort-new')])
            if state()['counts']['PUT']!=before_storage['counts']['PUT']+1:raise AssertionError('Best-effort deletion suppressed replacement')
            # Upload failure retains the DB; already deleted old objects are not restored.
            item=create([('old.jpg','image/jpeg',b'upload-failure-old')]);unchanged=row(item['postId']);before_storage=state()
            f3.expect(endpoint,'/__f3__/fail-next-put',200,'POST',dict(controls,**{'Content-Type':'application/json'}),json.dumps({'after':0,'count':1,'status':400}).encode())
            put(item['postId'],{'title':'실패 변경'},[('new.jpg','image/jpeg',b'upload-failure-new')],500)
            if row(item['postId'])!=unchanged or events(item['postId']) or state()['counts']['DELETE']!=before_storage['counts']['DELETE']+1:
                raise AssertionError('Upload failure changed DB or restored/retried old media')
            put(item['postId'],{'title':'복구 변경'},[('new.jpg','image/jpeg',b'upload-recovered')])
            # One actual SQL rollback through the packaged bean graph; near-PG tests cover both later failure points.
            item=create([('old.jpg','image/jpeg',b'rollback-old')]);unchanged=row(item['postId']);before_storage=state()
            f3.sql('REVOKE INSERT ON pawbridge_community.post_search_documents FROM '+f3.WRITER)
            try:put(item['postId'],{'title':'rollback'},[('new.jpg','image/jpeg',b'rollback-new')],500)
            finally:f3.sql('GRANT INSERT ON pawbridge_community.post_search_documents TO '+f3.WRITER)
            if row(item['postId'])!=unchanged or events(item['postId']) or state()['counts']['DELETE']!=before_storage['counts']['DELETE']+1 or state()['counts']['PUT']!=before_storage['counts']['PUT']+1:
                raise AssertionError('SQL rollback caused DB changes or external compensation')
            put(item['postId'],{'title':'복구'},[('new.jpg','image/jpeg',b'rollback-recovered')])
            f3.expect(base,'/api/v1/posts/'+str(item['postId']),200,'DELETE',{'Authorization':'Bearer '+tokens['ROLE_USER']})
            f3.expect(base,'/api/v1/posts/read/'+str(item['postId']),404);put(item['postId'],expected=404)
            put(post_id,expected=401,token=None)
            if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)")!='0':
                raise AssertionError('PUT response leaked a DB transaction')
            result['post_put_get_delete_sdk_and_failures']='passed'
        finally:
            try:
                if app is not None:result['app_sigterm_exit']=f3.stop_owned_child(app,'F5 packaged application')
            finally:
                if stub is not None:result['stub_sigterm_exit']=f3.stop_owned_child(stub,'F5 S3 stub')
                (EVIDENCE/'f5-packaged-result.txt').write_text(json.dumps(result,indent=2)+'\n')
    text=f4.fresh_log(app_path,app_offset)
    if text.count('Shutdown completed.')<1 or 'Graceful shutdown complete' not in text:raise AssertionError('F5 graceful pool/HTTP shutdown evidence missing')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')")!='0':raise AssertionError('F5 application connections remain')
    result.update(status='passed',shutdown_pools=1,remaining_application_connections=0)
    (EVIDENCE/'f5-packaged-result.txt').write_text(json.dumps(result,indent=2)+'\n')
    print('Hosted F5 packaged PUT/GET/DELETE, real SDK replacement, failures and normal termination passed')


if __name__=='__main__':
    f2.require_hosted()
    {'prepare':prepare,'smoke':smoke,'cleanup':f3.cleanup}[sys.argv[1]]()
