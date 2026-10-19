#!/usr/bin/env python3
"""Controlled F28 delta/cleanup/journey tests, not SQL, issuer, process or SSE evidence.

Only temporary synthetic files and stdlib mocks are used when a separate owner
runs these tests. The original business oracle and F26 transport are not copied.
"""
import ast
import base64
from contextlib import contextmanager
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import f28_stream_probe as probe

KEY = 'F28-unit-only-secret-' * 4
NOW = 1800000000
CREATED = '2026-10-20T00:00:00.123456Z'
READ_AT = '2026-10-20T00:00:30.654321Z'
EXPIRES = '2026-10-20T01:00:00.123456Z'


def owned():
    return dict(users=probe.USERS, notes=probe.NOTE_IDS, request=probe.REQUEST_KEY,
                created=CREATED, read_at=READ_AT, expires=EXPIRES)


def baseline():
    value = {str(i): [] for i in range(14)}
    value.update(private_notes=[], private_note_mailboxes=[], private_note_requests=[])
    return value


def snapshot():
    value = baseline()
    value['private_notes'] = [dict(note_id=n, sender_id=probe.CALLER if n == probe.B else probe.OTHER,
        recipient_id=probe.OTHER if n == probe.B else probe.CALLER, body=probe.BODY,
        reply_to=probe.B if n == probe.C else None, context_type=None, context_id=None,
        created_at=CREATED, expires_at=EXPIRES) for n in probe.NOTE_IDS]
    value['private_note_mailboxes'] = [dict(member_id=m, note_id=n,
        direction='SENT' if m == probe.OTHER or n == probe.B else 'INBOX',
        read_at=READ_AT if m == probe.OTHER or n == probe.C else None,
        favorite=m == probe.CALLER and n in (probe.A, probe.C), created_at=CREATED) for m, n in probe.BOX_KEYS]
    value['private_note_requests'] = [dict(sender_id=probe.CALLER, request_id=probe.REQUEST_ID,
        request_hash=probe.REQUEST_HASH, note_id=probe.B, created_at=CREATED)]
    return value


def remove_owned(state, note):
    """Controlled server-side mutation, deliberately independent of producer delta()."""
    state['private_note_mailboxes'] = [r for r in state['private_note_mailboxes']
                                     if not (r['member_id'] == probe.CALLER and r['note_id'] == note)]
    if not any(r['note_id'] == note for r in state['private_note_mailboxes']):
        state['private_notes'] = [r for r in state['private_notes'] if r['note_id'] != note]
        for row in state['private_notes']:
            if row['reply_to'] == note: row['reply_to'] = None
        for row in state['private_note_requests']:
            if row['note_id'] == note: row['note_id'] = None


def notification_body(kind):
    selected = (probe.C, probe.A) if kind == 'initial' else (probe.C,) if kind == 'read' else ()
    return dict(code=200, message=probe.SUCCESS, data=dict(content=[dict(noteId=n, kind='PRIVATE_NOTE',
        actorId=probe.OTHER, actorNickname=probe.NAMES[probe.OTHER], createdAt=CREATED,
        read=n == probe.C, href='/notes/' + n) for n in selected], nextCursor=None, unreadCount=1 if kind == 'initial' else 0))


def surviving_view(other=False):
    return dict(noteId=probe.A if other else probe.C, body=probe.BODY, direction='SENT' if other else 'INBOX',
        createdAt=CREATED, readAt=READ_AT, favorite=not other, counterpartId=probe.CALLER if other else probe.OTHER,
        counterpartNickname=probe.NAMES[probe.CALLER if other else probe.OTHER], canReply=not other, contextHref=None)


def signed(identity):
    encode = lambda v: base64.urlsafe_b64encode(json.dumps(v, separators=(',', ':')).encode()).rstrip(b'=').decode()
    parts = [encode(dict(alg='HS512')), encode(dict(userId=identity, role='ROLE_USER', exp=NOW + 600))]
    parts.append(base64.urlsafe_b64encode(hmac.new(KEY.encode(), '.'.join(parts).encode(), hashlib.sha512).digest()).rstrip(b'=').decode())
    return '.'.join(parts)


class F28StreamProbeTest(unittest.TestCase):
    def test_only_existing_generic_transport_is_imported_without_new_jwt_or_expiry_matrix(self):
        tree = ast.parse(Path(probe.__file__).read_text())
        imports = [n for n in tree.body if isinstance(n, ast.ImportFrom) and n.module == 'f26_stream_probe']
        self.assertEqual([['Stream', 'close_streams']], [[a.name for a in n.names] for n in imports])
        self.assertFalse(any(isinstance(n, ast.ClassDef) for n in tree.body))
        self.assertNotIn('crafted_input', [n.name for n in tree.body if isinstance(n, ast.FunctionDef)])

    def test_owned_normal_token_file_reads_only_two_new_keys_and_preserves_previous_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); path = root / 'f3-tokens-42-1.json'
            values = dict(F28_CALLER=signed(probe.CALLER), F28_OTHER=signed(probe.OTHER), old_expired='unused-preserved')
            path.write_text(json.dumps(values)); path.chmod(0o600); before = path.read_bytes()
            ci = types.SimpleNamespace(f2=types.SimpleNamespace(require_hosted=lambda: '42-1'))
            with patch.dict(os.environ, dict(RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path), F3_TEST_JWT_SECRET=KEY)), \
                    patch.object(probe.time, 'time', return_value=NOW):
                self.assertEqual({'F28_CALLER', 'F28_OTHER'}, set(probe.load_tokens(ci)))
                path.chmod(0o644)
                with self.assertRaises(SystemExit): probe.load_tokens(ci)
            self.assertEqual(before, path.read_bytes())

    def test_seed_reserves_user_note_mailbox_request_reply_and_block_references_before_atomic_insert(self):
        ci = Mock(NOTES='notes', MAILBOXES='mailboxes', REQUESTS='requests'); ci.owned_sql.return_value = '0'
        result = probe.seed(ci); reservation, insert = [c.args[0] for c in ci.owned_sql.call_args_list]
        self.assertIn('reply_to IN', reservation); self.assertIn('FROM requests', reservation)
        self.assertIn('private_note_blocks', reservation); self.assertIn('contact_deletions', reservation)
        self.assertTrue(insert.startswith('BEGIN;')); self.assertTrue(insert.endswith('COMMIT;'))
        self.assertIn('(sender_id,request_id,request_hash,note_id,created_at)', insert)
        self.assertEqual(probe.REQUEST_KEY, result['request']); self.assertEqual(probe.NOTE_IDS, result['notes'])
        for identity in probe.USERS:
            self.assertIn(probe.NAMES[identity], insert); self.assertIn('f28-' + str(identity) + '@fixture.invalid', insert)
        self.assertNotIn('CREATE ', insert); self.assertNotIn('TRUNCATE ', insert)

    def test_seed_collision_never_acknowledges_cleanup_or_writes_business_rows(self):
        ci = Mock(NOTES='notes', MAILBOXES='mailboxes', REQUESTS='requests'); ci.owned_sql.return_value = '1'
        with self.assertRaises(SystemExit): probe.seed(ci)
        self.assertEqual(1, ci.owned_sql.call_count); self.assertTrue(ci.owned_sql.call_args.args[0].startswith('SELECT '))

    def test_first_mailbox_delete_preserves_other_mailbox_original_and_all_read_favorite_values(self):
        before = snapshot(); after = copy.deepcopy(before); remove_owned(after, probe.A)
        self.assertEqual(dict(deleted_mailbox_rows=1, deleted_note_rows=0, cleared_request_links=0, cleared_reply_links=0),
                         probe.deletion_delta(before, after, probe.A))
        self.assertEqual(before['private_notes'], after['private_notes'])
        self.assertEqual(4, len(before['private_note_mailboxes']))
        self.assertIn((probe.OTHER, probe.A), {(r['member_id'], r['note_id']) for r in after['private_note_mailboxes']})

    def test_orphan_delete_allows_only_owned_request_note_and_child_reply_set_null(self):
        before = snapshot(); remove_owned(before, probe.A); after = copy.deepcopy(before); remove_owned(after, probe.B)
        self.assertEqual(dict(deleted_mailbox_rows=1, deleted_note_rows=1, cleared_request_links=1, cleared_reply_links=1),
                         probe.deletion_delta(before, after, probe.B))
        self.assertIsNone(after['private_note_requests'][0]['note_id'])
        self.assertIsNone(next(r for r in after['private_notes'] if r['note_id'] == probe.C)['reply_to'])
        self.assertEqual(probe.B, before['private_note_requests'][0]['note_id'])

    def test_final_read_inbox_delete_leaves_the_other_sent_box_and_null_request(self):
        before = snapshot(); remove_owned(before, probe.A); remove_owned(before, probe.B)
        after = copy.deepcopy(before); remove_owned(after, probe.C)
        self.assertEqual(dict(deleted_mailbox_rows=1, deleted_note_rows=1, cleared_request_links=0, cleared_reply_links=0),
                         probe.deletion_delta(before, after, probe.C))
        self.assertEqual([(probe.OTHER, probe.A)], [(r['member_id'], r['note_id']) for r in after['private_note_mailboxes']])
        self.assertEqual([probe.A], [r['note_id'] for r in after['private_notes']])

    def test_delta_rejects_extra_row_column_fk_or_missing_transition_changes(self):
        before = snapshot()
        for defect in ('other_box', 'original', 'read_at', 'favorite', 'request', 'extra_table', 'missing'):
            after = copy.deepcopy(before); remove_owned(after, probe.A)
            if defect == 'other_box': after['private_note_mailboxes'] = [r for r in after['private_note_mailboxes'] if r['member_id'] != probe.OTHER]
            elif defect == 'original': after['private_notes'] = [r for r in after['private_notes'] if r['note_id'] != probe.A]
            elif defect == 'read_at': after['private_note_mailboxes'][0]['read_at'] = None
            elif defect == 'favorite': after['private_note_mailboxes'][0]['favorite'] = True
            elif defect == 'request': after['private_note_requests'][0]['note_id'] = None
            elif defect == 'extra_table': after['0'].append(dict(unexpected=True))
            else: after = copy.deepcopy(before)
            with self.subTest(defect=defect), self.assertRaises(AssertionError): probe.deletion_delta(before, after, probe.A)
        with self.assertRaises(ValueError): probe.deletion_delta(before, before, 'unowned')

    def test_delta_refuses_unowned_request_or_reply_reference_instead_of_allowing_broad_fk_changes(self):
        for foreign in ('request', 'reply'):
            before = snapshot()
            if foreign == 'request': before['private_note_requests'].append(dict(sender_id=99, request_id='foreign', note_id=probe.B))
            else: before['private_notes'].append(dict(note_id='foreign', reply_to=probe.B))
            after = copy.deepcopy(before); remove_owned(after, probe.B)
            with self.subTest(foreign=foreign), self.assertRaises(AssertionError): probe.deletion_delta(before, after, probe.B)

    def test_notifications_keep_read_state_and_become_empty_only_after_the_last_inbox_delete(self):
        for kind in ('initial', 'read', 'empty'):
            body = notification_body(kind); probe.verify_view(body, 'notifications_' + kind, owned())
            body['data']['unreadCount'] = True
            with self.assertRaises(AssertionError): probe.verify_view(body, 'notifications_' + kind, owned())

    def test_surviving_other_detail_and_inbox_require_exact_raw_ids_flags_and_microsecond_read_at(self):
        other = dict(code=200, message=probe.SUCCESS, data=surviving_view(True))
        inbox = dict(code=200, message=probe.SUCCESS, data=dict(content=[surviving_view()], totalElements=1, totalPages=1, number=0, size=10))
        probe.verify_view(other, 'other_detail', owned()); probe.verify_view(inbox, 'inbox', owned())
        for defect in ('read_at', 'numeric_flag', 'extra'):
            bad = copy.deepcopy(other)
            if defect == 'read_at': bad['data']['readAt'] = None
            elif defect == 'numeric_flag': bad['data']['favorite'] = 0
            else: bad['data']['extra'] = 1
            with self.subTest(defect=defect), self.assertRaises(AssertionError): probe.verify_view(bad, 'other_detail', owned())

    def cleanup_ci(self, state=None):
        ci = Mock(NOTES='notes', MAILBOXES='mailboxes', REQUESTS='requests')
        ci.database_snapshot.return_value = snapshot() if state is None else state
        ci.owned_sql.side_effect = ['0', '2', '']
        return ci

    def test_cleanup_reobserves_any_acknowledged_delete_stage_and_uses_exact_fk_order(self):
        state = snapshot()
        for stage in (None, probe.A, probe.B, probe.C):
            if stage is not None: remove_owned(state, stage)
            ci = self.cleanup_ci(copy.deepcopy(state)); probe.cleanup(ci, owned())
            ci.database_snapshot.assert_called_once(); self.assertEqual(2, ci.require_owner.call_count)
            guard, identity, delete = [c.args[0] for c in ci.owned_sql.call_args_list]
            for name in ('FROM notes', 'FROM mailboxes', 'FROM requests', 'private_note_blocks', 'contact_deletions'):
                self.assertIn(name, guard)
            self.assertIn('(user_id,email,nickname)', identity)
            order = [delete.index('DELETE FROM ' + t) for t in ('requests', 'mailboxes', 'notes', 'pawbridge_community.private_note_members', 'pawbridge_user.users')]
            self.assertEqual(sorted(order), order); self.assertTrue(delete.endswith('COMMIT;'))
            self.assertIn("note_id='" + probe.C + "'::uuid", delete)

    def test_cleanup_refuses_unacknowledged_or_foreign_keys_without_deleting(self):
        ci = self.cleanup_ci()
        with self.assertRaises(ValueError): probe.cleanup(ci, dict(users=(), notes=probe.NOTE_IDS, request=probe.REQUEST_KEY))
        ci.owned_sql.assert_not_called()
        for foreign in ('request', 'mailbox', 'reference_sql'):
            state = snapshot(); ci = self.cleanup_ci(state)
            if foreign == 'request': state['private_note_requests'].append(dict(sender_id=99, request_id='foreign', note_id=probe.B))
            elif foreign == 'mailbox': state['private_note_mailboxes'].append(dict(member_id=99, note_id=probe.A))
            else: ci.owned_sql.side_effect = ['1']
            with self.subTest(foreign=foreign), self.assertRaises(SystemExit): probe.cleanup(ci, owned())
            self.assertTrue(all(c.args[0].startswith('SELECT ') for c in ci.owned_sql.call_args_list))

    def test_literal_journey_calls_and_counter_contract_balance_without_transport_matrix_duplication(self):
        tree = ast.parse(Path(probe.__file__).read_text())
        calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == 'check']
        deletes = [n for n in calls if any(k.arg == 'method' and isinstance(k.value, ast.Constant) and k.value.value == 'DELETE' for k in n.keywords)]
        values = probe.EXPECTED_JAR_COUNTS
        self.assertEqual(16, len(calls)); self.assertEqual(9, len(deletes))
        self.assertEqual(18, values['request_checks']); self.assertEqual(16 + 2, values['request_checks'])
        self.assertEqual(values['delete_checks'], values['successful_delete_checks'] + values['rejected_delete_checks'])
        self.assertEqual(values['request_checks'], values['delete_checks'] + values['get_checks'])
        self.assertEqual((3, 2, 1, 1), tuple(values[k] for k in ('deleted_mailbox_rows', 'deleted_note_rows', 'cleared_request_links', 'cleared_reply_links')))
        self.assertEqual(0, values['other_resync_checks']); self.assertNotIn('short_expiry_checks', values)

    def test_unmeasured_or_mismatched_prefix_never_loads_tokens_or_claims_passed(self):
        for gate in ('unmeasured', 'mismatch'):
            with self.subTest(gate=gate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=root,
                    require_measurements=Mock(side_effect=SystemExit('unmeasured') if gate == 'unmeasured' else None),
                    COUNTERS=(), EXPECTED_JAR_COUNTS=None)
                with patch.object(probe, 'load_tokens') as load, self.assertRaises(SystemExit): probe.run(ci)
                load.assert_not_called()
                self.assertEqual('incomplete', json.loads((root / 'f28-packaged-result.txt').read_text())['status'])

    def controlled_journey(self, fail_body=False, fail_stop=False, stale=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True); jar.write_bytes(b'controlled-not-a-real-jar')
            app, stub = Mock(), Mock(); state = baseline(); events = []; fault = [None]; pushes = [0]; expect_calls = [0]
            stale_input = [False]; requests = []
            def seeded(_): state.clear(); state.update(snapshot()); return owned()
            def cleaned(_ci, keys):
                self.assertEqual(probe.REQUEST_KEY, keys['request'])
                if fail_body:
                    self.assertIsNone(state['private_note_requests'][0]['note_id'])
                    self.assertIsNone(next(r for r in state['private_notes'] if r['note_id'] == probe.C)['reply_to'])
                events.append('cleanup-reobserved'); state.clear(); state.update(baseline())
            @contextmanager
            def denied(kind):
                fault[0] = kind
                try: yield
                finally: fault[0] = None
            def expect(*_a, **_k):
                expect_calls[0] += 1
                if stale and expect_calls[0] == 6: stale_input[0] = True
                return dict(counts={}, requests=[], objects={})
            def tokens(_):
                if stale_input[0]: raise SystemExit('controlled stale before actual request')
                return dict(F28_CALLER='unit-caller', F28_OTHER='unit-other')
            def request(_base, path, **kwargs):
                requests.append((kwargs['method'], path))
                status, data, cache = 200, None, 'no-store'
                if not kwargs['headers'].get('Authorization'):
                    status, data, cache = 401, dict(code=401, message='인증 토큰이 필요합니다.', data=None), probe.SECURITY_CACHE
                elif path.endswith('/extra'):
                    status, data, cache = 403, dict(code=403, message='접근할 수 없습니다.', data=None), probe.SECURITY_CACHE
                elif kwargs['method'] == 'DELETE':
                    note = path.rsplit('/', 1)[1]
                    if fault[0]: status, data = 503, dict(message=probe.MEMBER_UNAVAILABLE if fault[0] == 'nickname' else probe.STORAGE)
                    elif not any(r['member_id'] == probe.CALLER and r['note_id'] == note for r in state['private_note_mailboxes']):
                        status, data = 404, dict(message=probe.MISSING)
                    else:
                        remove_owned(state, note); pushes[0] += 1
                        if fail_body and note == probe.B: return 200, b'bad body after controlled FK commit', {'Cache-Control': cache}
                elif path.endswith('/notifications'):
                    own_notes = {r['note_id'] for r in state['private_note_mailboxes'] if r['member_id'] == probe.CALLER}
                    data = notification_body('initial' if probe.A in own_notes else 'read' if probe.C in own_notes else 'empty')
                elif path == '/api/v1/notes':
                    data = dict(code=200, message=probe.SUCCESS, data=dict(content=[surviving_view()], totalElements=1, totalPages=1, number=0, size=10))
                elif kwargs['headers']['Authorization'].endswith('unit-other'):
                    data = dict(code=200, message=probe.SUCCESS, data=surviving_view(True))
                else: status, data = 404, dict(message=probe.MISSING)
                return status, json.dumps(data).encode() if data is not None else b'', {'Cache-Control': cache}
            def stop(child, _label):
                events.append('stop-app' if child is app else 'stop-stub')
                if fail_stop and child is app: raise RuntimeError('controlled stop failure')
                return 143
            class FakeStream:
                def __init__(self, _base, headers): self.other = headers['Authorization'].endswith('unit-other')
                def wait(self, kind, count=1):
                    if kind == 'resync': self.assert_resync(count)
                def assert_resync(self, count):
                    if count != (0 if self.other else pushes[0]): raise AssertionError('controlled hint differs')
                def wait_eof(self): events.append('server-eof')
                def close(self): events.append('stream-close')
            log = 'Start completed.\n' * 2 + 'Shutdown completed.\n' * 2 + 'Graceful shutdown complete\nPrivate note stream resources stopped\n'
            ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=root, ROOT=root,
                require_measurements=Mock(), COUNTERS=probe.COUNTERS, EXPECTED_JAR_COUNTS=probe.EXPECTED_JAR_COUNTS,
                database_snapshot=lambda: copy.deepcopy(state), owned_sql=lambda *_: '0', denied_privilege=denied,
                f4=types.SimpleNamespace(start_ready_children=lambda *_: (app, stub, 'base', 'endpoint', 1, 0), fresh_log=lambda *_: log),
                f3=types.SimpleNamespace(WRITER='writer', expect=expect, request=request, stop_owned_child=stop))
            env = dict(GITHUB_SHA='a' * 40, F2_TEST_JDBC_URL='synthetic', F2_TEST_DB_USER='reader', F2_TEST_DB_PASSWORD='p1',
                       F3_TEST_WRITER_PASSWORD='p2', F3_TEST_JWT_SECRET=KEY)
            with patch.dict(os.environ, env), patch.object(probe, 'load_tokens', side_effect=tokens), \
                    patch.object(probe, 'Stream', FakeStream), patch.object(probe, 'seed', side_effect=seeded), patch.object(probe, 'cleanup', side_effect=cleaned):
                if fail_stop:
                    with self.assertRaisesRegex(RuntimeError, 'controlled stop'): probe.run(ci)
                elif fail_body:
                    with self.assertRaisesRegex(AssertionError, 'empty original 200'): probe.run(ci)
                elif stale:
                    with self.assertRaisesRegex(SystemExit, 'controlled stale'): probe.run(ci)
                else: probe.run(ci)
            return json.loads((root / 'f28-packaged-result.txt').read_text()), events, requests

    def test_controlled_journey_balances_requests_fk_deltas_resyncs_and_cleanup_before_passed(self):
        result, events, requests = self.controlled_journey()
        self.assertEqual('passed', result['status']); self.assertEqual(16, len(requests))
        self.assertEqual(probe.EXPECTED_JAR_COUNTS, {k: result[k] for k in probe.COUNTERS})
        self.assertEqual(['stop-app', 'server-eof', 'server-eof', 'stream-close', 'stream-close', 'stop-stub', 'cleanup-reobserved'], events)
        self.assertTrue(result['stream_resources_stopped']); self.assertTrue(result['owned_cleanup_restored'])

    def test_body_failure_after_orphan_fk_commit_and_child_stop_failure_still_attempt_all_cleanup(self):
        for failed_stop in (False, True):
            with self.subTest(failed_stop=failed_stop):
                result, events, requests = self.controlled_journey(fail_body=True, fail_stop=failed_stop)
                self.assertEqual(8, len(requests)); self.assertEqual('incomplete', result['status'])
                self.assertEqual(['stop-app', 'stream-close', 'stream-close', 'stop-stub', 'cleanup-reobserved'], events)
                self.assertNotIn('owned_cleanup_restored', result)

    def test_token_stale_after_pre_request_snapshot_stops_before_request_and_cleans_all_resources(self):
        result, events, requests = self.controlled_journey(stale=True)
        self.assertEqual([], requests); self.assertEqual('incomplete', result['status'])
        self.assertEqual(['stop-app', 'stream-close', 'stream-close', 'stop-stub', 'cleanup-reobserved'], events)


if __name__ == '__main__':
    unittest.main()
