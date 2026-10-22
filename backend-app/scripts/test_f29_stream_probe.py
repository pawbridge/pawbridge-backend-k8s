"""Pure synthetic row/stream/journey mocks; no SQL, process, socket or actual issuer run."""
from contextlib import contextmanager
import base64
import copy
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import types
import unittest
from unittest.mock import Mock, patch

import f29_stream_probe as probe

REAL_NOTE_STREAM = probe.NoteStream
KEY='F29-unit-only-secret-'*4
NOW=1800000000


def signed(identity):
    encode=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=').decode()
    head=encode(json.dumps(dict(alg='HS256')).encode())
    body=encode(json.dumps(dict(userId=identity,role='ROLE_USER',exp=NOW+600)).encode())
    return head+'.'+body+'.'+encode(hmac.new(KEY.encode(),(head+'.'+body).encode(),hashlib.sha256).digest())

CREATED = '2032-02-29T15:00:00.123456Z'
EXPIRES = '2033-02-28T15:00:00.123456Z'
IDS = tuple('f2900000-0000-4000-8000-' + f'{i:012d}' for i in (201, 202, 203))


def empty():
    return {name: [] for name in ('users', 'contact_deletions', 'private_note_members', 'private_note_blocks',
            'private_notes', 'private_note_mailboxes', 'private_note_requests', 'posts', 'animal_reports',
            *('protected_' + str(i) for i in range(9)))}


def seeded():
    state = empty()
    state['users'] = [dict(user_id=i, email='f29-' + str(i) + '@fixture.invalid', nickname=probe.NAMES[i]) for i in probe.USERS]
    state['private_note_members'] = [dict(member_id=i) for i in probe.USERS]
    return state


def add_send(state, index=0):
    key, identity = probe.REQUEST_IDS[index], IDS[index]
    state['private_notes'].append(dict(note_id=identity, sender_id=probe.CALLER, recipient_id=probe.OTHER,
        body=probe.BODY, reply_to=None, context_type=None, context_id=None, created_at=CREATED, expires_at=EXPIRES))
    for member in probe.USERS:
        state['private_note_mailboxes'].append(dict(member_id=member, note_id=identity,
            direction='SENT' if member == probe.CALLER else 'INBOX',
            read_at=CREATED if member == probe.CALLER else None, favorite=False, created_at=CREATED))
    state['private_note_requests'].append(dict(sender_id=probe.CALLER, request_id=key,
        request_hash=probe.request_hash(), note_id=identity, created_at=CREATED))
    for table, order in (('private_notes', lambda r:r['note_id']), ('private_note_mailboxes', lambda r:(r['member_id'],r['note_id'])),
                          ('private_note_requests', lambda r:(r['sender_id'],r['request_id']))): state[table].sort(key=order)


def payload(index=0):
    return dict(noteId=IDS[index], kind='PRIVATE_NOTE', actorId=probe.CALLER, actorNickname=probe.NAMES[probe.CALLER],
                createdAt=CREATED.replace('123456Z', '123456789Z'), read=False, href='/notes/' + IDS[index])


def stream(notes=()):
    value = REAL_NOTE_STREAM.__new__(REAL_NOTE_STREAM)
    value.notes = list(notes); value.events = dict(ready=1, resync=0, heartbeat=0, note=len(notes))
    value.condition = threading.Condition(); value.failed = value.eof = value.closed = False; value.bytes = 0
    value.close = Mock()
    return value


def rest(record,kind):
    note=record['note'];receiver=kind!='sent'
    if kind=='notifications':
        data=dict(content=[payload()],nextCursor=None,unreadCount=1)
    else:
        row=dict(noteId=IDS[0],body=probe.BODY,direction='INBOX' if receiver else 'SENT',
            createdAt=CREATED,readAt=None if receiver else CREATED,favorite=False,
            counterpartId=probe.CALLER if receiver else probe.OTHER,
            counterpartNickname=probe.NAMES[probe.CALLER if receiver else probe.OTHER],canReply=receiver,contextHref=None)
        data=row if kind=='detail' else dict(content=[row],totalElements=1,totalPages=1,number=0,size=10)
    return dict(code=200,message=probe.SUCCESS,data=data)


def exercise_journey(directory,fail_after_commit=False,extra_log=''):
    """Single controlled process/HTTP/SQL seam, not an alternative application implementation."""
    root=Path(directory);(root/'boot/build/libs').mkdir(parents=True);(root/'boot/build/libs/pawbridge-backend-app.jar').write_bytes(b'unit marker')
    state=empty();owned=dict(users=probe.USERS,ack={});opened=[];denial=[None];cleanup_ack=[]
    app,stub=object(),object()
    ci=types.SimpleNamespace(ROOT=root,EVIDENCE=root,SOURCE='fixed-source',COUNTERS=probe.COUNTERS,
         EXPECTED_JAR_COUNTS=probe.EXPECTED_JAR_COUNTS,require_fixture=Mock(return_value='42-1'),
         require_owner=Mock(return_value='42-1'),require_measurements=Mock(),database_snapshot=lambda:copy.deepcopy(state),
         owned_sql=Mock(return_value='0'))
    @contextmanager
    def denied(kind):
        denial[0]=kind
        try:yield
        finally:denial[0]=None
    ci.denied_privilege=denied
    def seed(_):state.update(seeded());return owned
    def cleanup(_ci,value):
        # The real ownership/cleanup function is tested separately. Here only orchestrator order is mocked.
        probe.committed_records(state,value);cleanup_ack.append(len(value['ack']));state.clear();state.update(empty())
    def opening(*_):
        value=stream();opened.append(value);return value
    def start(*args):
        args[-1].write_text('Start completed.\n'+'Shutdown completed.\n'+'Graceful shutdown complete\nPrivate note stream resources stopped\n'+extra_log)
        return app,stub,'http://127.0.0.1:1','http://127.0.0.1:2',1,0
    def stop(child,_):
        if child is app:
            for value in opened:value.eof=True
        return 143
    def request(_base,path,method='GET',headers=None,body=None):
        status=200;message=None;security=False
        if not headers.get('Authorization'):status,message,security=401,'인증 토큰이 필요합니다.',True
        elif path.endswith('/extra/extra'):status,message,security=403,'접근할 수 없습니다.',True
        elif method=='POST':
            sent=json.loads(body);key=sent['requestId'];records=probe.committed_records(state,owned)
            if denial[0]:status,message=503,probe.prior.STORAGE if denial[0]=='note-insert' else probe.prior.MEMBER_UNAVAILABLE
            elif key in records:
                if sent['body']!=probe.RAW_BODY:status,message=409,probe.CONFLICT
                else:data=dict(noteId=records[key]['note']['note_id'])
            else:
                index=probe.REQUEST_IDS.index(key);add_send(state,index)
                if fail_after_commit:raise ConnectionError('controlled response loss after commit')
                opened[1].notes.append(payload(index));opened[1].events['note']+=1;data=dict(noteId=IDS[index])
        else:
            records=probe.committed_records(state,owned);record=records[probe.REQUEST_IDS[0]]
            kind='notifications' if path.endswith('/notifications') else 'sent' if '?box=SENT' in path else 'inbox' if path=='/api/v1/notes' else 'detail'
            return 200,json.dumps(rest(record,kind)).encode(),{'Cache-Control':'no-store'}
        result=(dict(code=status,message=message,data=None) if security else {'message':message}) if message else dict(code=200,message=probe.SUCCESS,data=data)
        return status,json.dumps(result).encode(),{'Cache-Control':probe.prior.SECURITY_CACHE if security else 'no-store'}
    ci.f2=types.SimpleNamespace(require_hosted=lambda:'42-1')
    ci.f3=types.SimpleNamespace(WRITER='writer',APP='app',expect=Mock(return_value=dict(counts={'GET':0},objects={},requests=[])),
                               request=Mock(side_effect=request),stop_owned_child=Mock(side_effect=stop))
    ci.f4=types.SimpleNamespace(start_ready_children=Mock(side_effect=start),fresh_log=lambda path,_:path.read_text())
    with patch.object(probe,'seed',side_effect=seed),patch.object(probe,'cleanup',side_effect=cleanup), \
            patch.object(probe,'load_tokens',return_value=dict(F29_CALLER='unit-caller',F29_OTHER='unit-other')), \
            patch.object(probe,'NoteStream',side_effect=opening), \
            patch.object(probe.transport,'_app_environment',return_value=({},{})), \
            patch.object(probe.time,'time_ns',return_value=probe.instant_ns(CREATED)), \
            patch.dict(os.environ,dict(F2_TEST_DB_PASSWORD='db-unit',F3_TEST_WRITER_PASSWORD='writer-unit',F3_TEST_JWT_SECRET=KEY)), \
            patch('builtins.print'):
        if fail_after_commit:
            with unittest.TestCase().assertRaises(ConnectionError):probe.run(ci)
        else:probe.run(ci)
    return json.loads((root/'f29-packaged-result.txt').read_text()),ci,owned,cleanup_ack


class SendProbeTest(unittest.TestCase):
    def test_nanosecond_wire_and_microsecond_storage_keep_precision_and_utc_leap_year_semantics(self):
        probe.same_instant(payload()['createdAt'], CREATED)
        with self.assertRaises(AssertionError): probe.same_instant(CREATED.replace('123456Z','123458Z'), CREATED)
        state=seeded();add_send(state)
        self.assertEqual(1,len(probe.committed_records(state,dict(users=probe.USERS,ack={}))))
        state['private_notes'][0]['expires_at']='2033-03-01T15:00:00.123456Z'
        with self.assertRaises(AssertionError):probe.committed_records(state,dict(users=probe.USERS,ack={}))

    def test_note_decoder_reuses_transport_budgets_and_rejects_body_or_unknown_events(self):
        for invalid in (False, True):
            value=stream(); note=payload()
            if invalid:note['body']='not allowed'
            wire=b'event:ready\ndata:{}\n\nevent:note\ndata:'+json.dumps(note).encode()+b'\n\n'
            value.events['ready']=0;value.response=io.BytesIO(wire);value._read()
            self.assertEqual(invalid,value.failed)
            self.assertEqual(0 if invalid else 1,value.events['note'])
            if not invalid:self.assertTrue(value.eof)
        value=stream();value.response=io.BytesIO(b'event:unexpected\ndata:{}\n\n');value._read();self.assertTrue(value.failed)

    def test_only_seven_notification_fields_and_exact_actor_identity_are_accepted(self):
        for key,value in (('actorId',probe.OTHER),('read',True),('href','/notes/wrong'),('actorNickname','normalized')):
            note=payload();note[key]=value
            with self.subTest(key=key),self.assertRaises(AssertionError):probe.validate_notification(note)

    def test_new_send_delta_allows_only_the_original_four_rows_and_preserves_all_other_tables(self):
        before=seeded();after=copy.deepcopy(before);add_send(after)
        owned=dict(users=probe.USERS,ack={});now=probe.instant_ns(CREATED)
        self.assertEqual(IDS[0],probe.send_delta(before,after,owned,probe.REQUEST_IDS[0],now,now)['note']['note_id'])
        after['posts'].append(dict(post_id=123))
        with self.assertRaises(AssertionError):probe.send_delta(before,after,owned,probe.REQUEST_IDS[0],now,now)

    def test_response_db_and_recipient_event_must_agree_before_acknowledging_generated_uuid(self):
        state=seeded();add_send(state);owned=dict(users=probe.USERS,ack={})
        record=probe.committed_records(state,owned)[probe.REQUEST_IDS[0]]
        response=dict(code=200,message=probe.SUCCESS,data=dict(noteId=IDS[0]))
        recipient,sender=stream([payload()]),stream()
        wrong=copy.deepcopy(response);wrong['data']['noteId']=IDS[1]
        with self.assertRaises(AssertionError):probe.acknowledge(owned,probe.REQUEST_IDS[0],wrong,record,recipient,sender)
        self.assertEqual({},owned['ack'])
        sender.notes=[payload()];sender.events['note']=1
        with self.assertRaises(AssertionError):probe.acknowledge(owned,probe.REQUEST_IDS[0],response,record,recipient,sender)
        self.assertEqual({},owned['ack'])
        sender.notes=[];sender.events['note']=0
        probe.acknowledge(owned,probe.REQUEST_IDS[0],response,record,recipient,sender)
        self.assertEqual({probe.REQUEST_IDS[0]:IDS[0]},owned['ack'])

    def test_partial_commits_foreign_mailboxes_replies_and_request_hash_changes_are_never_claimed(self):
        for kind in ('partial','mailbox','reply','hash'):
            state=seeded();add_send(state)
            if kind=='partial':state['private_note_requests'].clear()
            elif kind=='mailbox':state['private_note_mailboxes'].append(dict(state['private_note_mailboxes'][0],member_id=99))
            elif kind=='reply':state['private_notes'].append(dict(state['private_notes'][0],note_id=IDS[1],sender_id=99,recipient_id=100,reply_to=IDS[0]))
            else:state['private_note_requests'][0]['request_hash']='different'
            with self.subTest(kind=kind),self.assertRaises(AssertionError):probe.committed_records(state,dict(users=probe.USERS,ack={}))

    def test_unacknowledged_but_complete_commit_is_recovered_for_exact_fk_order_cleanup_without_becoming_success(self):
        state=seeded();add_send(state);owned=dict(users=probe.USERS,ack={})
        ci=Mock(NOTES='notes',MAILBOXES='mailboxes',REQUESTS='requests');ci.database_snapshot.return_value=state
        probe.cleanup(ci,owned);sql=ci.owned_sql.call_args.args[0]
        self.assertLess(sql.index('DELETE FROM requests'),sql.index('DELETE FROM mailboxes'))
        self.assertLess(sql.index('DELETE FROM mailboxes'),sql.index('DELETE FROM notes'))
        self.assertIn(IDS[0],sql);self.assertNotIn(IDS[1],sql);self.assertNotIn('CASCADE',sql);self.assertEqual({},owned['ack'])
        state['private_note_blocks']=[dict(member_id=probe.CALLER,blocked_id=99)];ci.owned_sql.reset_mock()
        with self.assertRaises(SystemExit):probe.cleanup(ci,owned)
        ci.owned_sql.assert_not_called()

    def test_seed_collision_stops_before_writes_and_success_seeds_only_two_users_and_members_atomically(self):
        ci=Mock(NOTES='notes',MAILBOXES='mailboxes',REQUESTS='requests');ci.owned_sql.return_value='1'
        with self.assertRaises(SystemExit):probe.seed(ci)
        self.assertEqual(1,ci.owned_sql.call_count)
        ci.owned_sql.side_effect=['0',''];ci.owned_sql.reset_mock();owned=probe.seed(ci)
        sql=ci.owned_sql.call_args.args[0];self.assertTrue(sql.startswith('BEGIN;'));self.assertTrue(sql.endswith('COMMIT;'))
        self.assertEqual({},owned['ack']);self.assertNotIn('INSERT INTO notes',sql);self.assertNotIn('CREATE ',sql)

    def test_normal_tokens_reuse_existing_signature_freshness_checks_and_never_rewrite_prior_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();path=root/'f3-tokens-42-1.json'
            path.write_text(json.dumps(dict(F29_CALLER=signed(probe.CALLER),F29_OTHER=signed(probe.OTHER),old='preserved')));path.chmod(0o600);before=path.read_bytes()
            ci=types.SimpleNamespace(f2=types.SimpleNamespace(require_hosted=lambda:'42-1'))
            with patch.dict(os.environ,dict(RUNNER_TEMP=str(root),F3_TEST_TOKENS_PATH=str(path),F3_TEST_JWT_SECRET=KEY)),patch.object(probe.time,'time',return_value=NOW):
                self.assertEqual({'F29_CALLER','F29_OTHER'},set(probe.load_tokens(ci)))
                path.chmod(0o644)
                with self.assertRaises(SystemExit):probe.load_tokens(ci)
            self.assertEqual(before,path.read_bytes())

    def test_unmeasured_reports_stop_before_token_socket_or_child_start_and_preserve_incomplete_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            ci=Mock(EVIDENCE=Path(directory),SOURCE='source');ci.require_fixture.return_value='42-1';ci.database_snapshot.return_value=empty()
            ci.require_measurements.side_effect=SystemExit('unmeasured')
            with patch.object(probe,'load_tokens') as tokens,self.assertRaises(SystemExit):probe.run(ci)
            tokens.assert_not_called();ci.f4.start_ready_children.assert_not_called()
            self.assertEqual('incomplete',json.loads((Path(directory)/'f29-packaged-result.txt').read_text())['status'])

    def test_exact_fifteen_request_journey_closes_replay_failure_recovery_delivery_and_resources_under_mocks(self):
        with tempfile.TemporaryDirectory() as directory:
            result,ci,owned,cleanup_ack=exercise_journey(directory)
        self.assertEqual('passed',result['status']);self.assertEqual(probe.EXPECTED_JAR_COUNTS,{k:result[k] for k in probe.COUNTERS})
        self.assertEqual(13,ci.f3.request.call_count);self.assertEqual(2,ci.f3.stop_owned_child.call_count)
        self.assertEqual([3],cleanup_ack);self.assertEqual(3,len(owned['ack']));self.assertEqual(2,result['server_closed_open_streams'])

    def test_response_loss_after_commit_still_stops_both_children_and_recovers_cleanup_without_ack_or_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            result,ci,owned,cleanup_ack=exercise_journey(directory,fail_after_commit=True)
        self.assertEqual('incomplete',result['status']);self.assertEqual({},owned['ack'])
        self.assertEqual([0],cleanup_ack);self.assertEqual(2,ci.f3.stop_owned_child.call_count)
        self.assertNotIn('owned_cleanup_restored',result)

    def test_json_escaped_unicode_body_in_child_log_prevents_passed_receipt_even_after_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(AssertionError,'private input'):
                exercise_journey(directory,extra_log=json.dumps(probe.BODY))
            result=json.loads((Path(directory)/'f29-packaged-result.txt').read_text())
        self.assertEqual('incomplete',result['status']);self.assertNotIn('owned_cleanup_restored',result)


if __name__=='__main__':unittest.main()
