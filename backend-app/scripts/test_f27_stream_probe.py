#!/usr/bin/env python3
"""Probe control tests only: no real PostgreSQL, issuer, process, socket or SSE.

The inherited F26 transport/parser/expiry/resource matrix is not duplicated.
Recording snapshots below are controlled inputs, not an independent SQL oracle.
"""
import ast
import base64
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

import f27_stream_probe as probe

KEY = 'F27-unit-only-secret-' * 4
NOW = 1800000000
CREATED = '2026-10-20T00:00:00.123456Z'
READ_AT = '2026-10-20T00:00:30.654321Z'


def owned():
    return dict(users=probe.USERS, notes=probe.NOTE_IDS, created=CREATED, read_at=READ_AT)


def snapshot():
    value = {str(i): [] for i in range(14)}
    value['private_notes'] = [dict(note_id=n, sender_id=probe.CALLER if n == probe.SENT else probe.OTHER,
                                  recipient_id=probe.OTHER if n == probe.SENT else probe.CALLER, body=probe.BODY) for n in probe.NOTE_IDS]
    value['private_note_mailboxes'] = [dict(member_id=probe.CALLER, note_id=n, direction='SENT' if n == probe.SENT else 'INBOX',
        read_at=None if n == probe.INBOX else READ_AT, favorite=False, created_at=CREATED) for n in probe.NOTE_IDS]
    return value


def signed(identity):
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=').decode()
    parts = [encode(dict(alg='HS512')), encode(dict(userId=identity, role='ROLE_USER', exp=NOW + 600))]
    parts.append(base64.urlsafe_b64encode(hmac.new(KEY.encode(), '.'.join(parts).encode(), hashlib.sha512).digest()).rstrip(b'=').decode())
    return '.'.join(parts)


def notifications():
    return dict(code=200, message=probe.SUCCESS, data=dict(content=[dict(noteId=n, kind='PRIVATE_NOTE', actorId=probe.OTHER,
        actorNickname=probe.NAMES[probe.OTHER], createdAt=CREATED, read=n != probe.INBOX, href='/notes/' + n)
        for n in (probe.READ_INBOX, probe.INBOX)], nextCursor=None, unreadCount=1))


class F27StreamProbeTest(unittest.TestCase):
    def test_only_generic_f26_stream_and_close_all_are_reused_without_new_expiry_matrix(self):
        tree = ast.parse(Path(probe.__file__).read_text())
        imported = [n for n in tree.body if isinstance(n, ast.ImportFrom) and n.module == 'f26_stream_probe']
        self.assertEqual([['Stream', 'close_streams']], [[v.name for v in n.names] for n in imported])
        self.assertFalse(any(isinstance(n, ast.ClassDef) for n in tree.body))
        self.assertNotIn('crafted_input', [n.name for n in tree.body if isinstance(n, ast.FunctionDef)])

    def test_normal_tokens_use_only_new_labels_and_owned_0600_without_overwriting_old_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'f3-tokens-42-1.json'
            values = dict(F27_CALLER=signed(probe.CALLER), F27_OTHER=signed(probe.OTHER), previous='preserved')
            path.write_text(json.dumps(values)); path.chmod(0o600)
            ci = types.SimpleNamespace(f2=types.SimpleNamespace(require_hosted=lambda: '42-1'))
            with patch.dict(os.environ, dict(RUNNER_TEMP=directory, F3_TEST_TOKENS_PATH=str(path), F3_TEST_JWT_SECRET=KEY)), patch.object(probe.time, 'time', return_value=NOW):
                self.assertEqual({'F27_CALLER', 'F27_OTHER'}, set(probe.load_tokens(ci)))
                path.chmod(0o644)
                with self.assertRaises(SystemExit): probe.load_tokens(ci)
                path.chmod(0o600)
                with patch.dict(os.environ, {'RUNNER_TEMP': str(Path(directory) / 'other')}), self.assertRaises(SystemExit): probe.load_tokens(ci)
            self.assertEqual(values, json.loads(path.read_text()))

    def test_first_favorite_transition_allows_one_owned_boolean_cell_and_preserves_read_at(self):
        before = snapshot(); after = copy.deepcopy(before)
        after['private_note_mailboxes'][0]['favorite'] = True
        self.assertTrue(probe.favorite_delta(before, after, probe.INBOX, True, True))
        self.assertFalse(before['private_note_mailboxes'][0]['favorite'])
        self.assertIsNone(after['private_note_mailboxes'][0]['read_at'])
        self.assertEqual(before['private_note_mailboxes'][1:], after['private_note_mailboxes'][1:])

    def test_duplicate_and_sent_false_transition_have_exact_zero_or_one_delta(self):
        before = snapshot(); before['private_note_mailboxes'][0]['favorite'] = True
        self.assertFalse(probe.favorite_delta(before, copy.deepcopy(before), probe.INBOX, True, False))
        before['private_note_mailboxes'][2]['favorite'] = True
        after = copy.deepcopy(before); after['private_note_mailboxes'][2]['favorite'] = False
        self.assertTrue(probe.favorite_delta(before, after, probe.SENT, False, True))
        self.assertEqual(READ_AT, after['private_note_mailboxes'][2]['read_at'])

    def test_delta_rejects_numeric_boolean_other_row_read_at_and_missing_transition(self):
        before = snapshot()
        for defect in ('numeric', 'read_at', 'other_row', 'missing'):
            after = copy.deepcopy(before); after['private_note_mailboxes'][0]['favorite'] = True
            if defect == 'numeric': after['private_note_mailboxes'][0]['favorite'] = 1
            elif defect == 'read_at': after['private_note_mailboxes'][0]['read_at'] = READ_AT
            elif defect == 'other_row': after['private_note_mailboxes'][1]['favorite'] = True
            else: after = copy.deepcopy(before)
            with self.subTest(defect=defect), self.assertRaises(AssertionError): probe.favorite_delta(before, after, probe.INBOX, True, True)
        with self.assertRaises(ValueError): probe.favorite_delta(before, before, probe.INBOX, 1, False)
        with self.assertRaises(ValueError): probe.favorite_delta(before, before, 'foreign', True, True)

    def test_fixed_projection_requires_primitive_favorite_null_read_at_and_exact_fields(self):
        favorites = dict.fromkeys(probe.NOTE_IDS, False); favorites[probe.INBOX] = True
        item = dict(noteId=probe.INBOX, body=probe.BODY, direction='INBOX', createdAt=CREATED, readAt=None,
                    favorite=True, counterpartId=probe.OTHER, counterpartNickname=probe.NAMES[probe.OTHER], canReply=True, contextHref=None)
        body = dict(code=200, message=probe.SUCCESS, data=item)
        probe.verify_view(body, 'detail', owned(), favorites)
        for defect in ('numeric', 'read_at', 'extra'):
            changed = copy.deepcopy(body)
            if defect == 'numeric': changed['data']['favorite'] = 1
            elif defect == 'read_at': changed['data']['readAt'] = READ_AT
            else: changed['data']['extra'] = 'unexpected'
            with self.subTest(defect=defect), self.assertRaises(AssertionError): probe.verify_view(changed, 'detail', owned(), favorites)

    def test_filtered_empty_and_sent_page_preserve_counts_existing_microsecond_read_at(self):
        favorites = dict.fromkeys(probe.NOTE_IDS, False)
        empty = dict(code=200, message=probe.SUCCESS, data=dict(content=[], totalElements=0, totalPages=0, number=0, size=10))
        probe.verify_view(empty, 'inbox_favorites', owned(), favorites)
        favorites[probe.SENT] = True
        item = dict(noteId=probe.SENT, body=probe.BODY, direction='SENT', createdAt=CREATED, readAt=READ_AT,
                    favorite=True, counterpartId=probe.OTHER, counterpartNickname=probe.NAMES[probe.OTHER], canReply=False, contextHref=None)
        body = dict(code=200, message=probe.SUCCESS, data=dict(content=[item], totalElements=1, totalPages=1, number=0, size=10))
        probe.verify_view(body, 'sent_favorites', owned(), favorites)
        body['data']['content'][0]['readAt'] = '2026-10-20T09:00:30.654321+09:00'
        with self.assertRaises(AssertionError): probe.verify_view(body, 'sent_favorites', owned(), favorites)

    def test_notifications_keep_original_read_and_unread_independent_of_favorite(self):
        favorites = dict.fromkeys(probe.NOTE_IDS, True)
        body = notifications(); probe.verify_view(body, 'notifications', owned(), favorites)
        body['data']['unreadCount'] = 0
        with self.assertRaises(AssertionError): probe.verify_view(body, 'notifications', owned(), favorites)
        body = notifications(); body['data']['content'][0]['read'] = 1
        with self.assertRaises(AssertionError): probe.verify_view(body, 'notifications', owned(), favorites)

    def test_seed_reserves_all_identities_atomically_with_unique_users_and_no_new_ddl(self):
        ci = Mock(); ci.NOTES = 'notes'; ci.MAILBOXES = 'mailboxes'; ci.owned_sql.return_value = '0'
        result = probe.seed(ci)
        query = ci.owned_sql.call_args.args[0]
        self.assertEqual(probe.USERS, result['users']); self.assertEqual(probe.NOTE_IDS, result['notes'])
        self.assertTrue(query.startswith('BEGIN;')); self.assertTrue(query.endswith('COMMIT;'))
        self.assertNotIn('CREATE ', query); self.assertNotIn('TRUNCATE ', query)
        for identity in probe.USERS:
            self.assertIn(probe.NAMES[identity], query); self.assertIn('f27-' + str(identity) + '@fixture.invalid', query)
        for note in probe.NOTE_IDS: self.assertEqual(2, query.count(note))
        self.assertIn('private_note_blocks', ci.owned_sql.call_args_list[0].args[0])

    def test_seed_collision_never_permits_cleanup_ownership_or_business_writes(self):
        ci = Mock(); ci.NOTES = 'notes'; ci.MAILBOXES = 'mailboxes'; ci.owned_sql.return_value = '1'
        with self.assertRaises(SystemExit): probe.seed(ci)
        self.assertEqual(1, ci.owned_sql.call_count)
        self.assertTrue(ci.owned_sql.call_args.args[0].startswith('SELECT '))

    def cleanup_ci(self):
        ci = Mock(); ci.NOTES = 'notes'; ci.MAILBOXES = 'mailboxes'
        ci.database_snapshot.return_value = snapshot()
        ci.database_snapshot.return_value['private_note_mailboxes'][0]['favorite'] = True
        ci.owned_sql.side_effect = ['0', '2', '']
        return ci

    def test_cleanup_reobserves_committed_favorite_and_restricts_exact_keys_in_fk_order(self):
        ci = self.cleanup_ci(); probe.cleanup(ci, owned()); ci.database_snapshot.assert_called_once()
        before, identity, delete = [call.args[0] for call in ci.owned_sql.call_args_list]
        self.assertIn('reply_to IN', before); self.assertIn('(user_id,email,nickname)', identity)
        self.assertLess(delete.index('DELETE FROM mailboxes'), delete.index('DELETE FROM notes'))
        self.assertLess(delete.index('DELETE FROM notes'), delete.index('DELETE FROM pawbridge_community.private_note_members'))
        self.assertLess(delete.index('DELETE FROM pawbridge_community.private_note_members'), delete.index('DELETE FROM pawbridge_user.users'))
        self.assertTrue(delete.endswith('COMMIT;'))

    def test_cleanup_refuses_unacknowledged_seed_or_foreign_reference_without_delete(self):
        ci = self.cleanup_ci()
        with self.assertRaises(ValueError): probe.cleanup(ci, dict(users=(), notes=probe.NOTE_IDS))
        ci.owned_sql.assert_not_called()
        ci.owned_sql.side_effect = ['1']
        with self.assertRaises(SystemExit): probe.cleanup(ci, owned())
        self.assertTrue(all(c.args[0].startswith('SELECT ') for c in ci.owned_sql.call_args_list))
        ci = self.cleanup_ci(); ci.database_snapshot.return_value['private_note_mailboxes'].append(dict(member_id=probe.OTHER, note_id=probe.INBOX))
        with self.assertRaises(SystemExit): probe.cleanup(ci, owned())
        ci.owned_sql.assert_not_called()

    def test_counter_contract_balances_real_request_categories_without_expiry_or_heartbeat_cases(self):
        values = probe.EXPECTED_JAR_COUNTS
        self.assertEqual(len(probe.COUNTERS), len(set(probe.COUNTERS)))
        self.assertEqual(set(values), set(probe.COUNTERS))
        self.assertEqual(18, values['request_checks'])
        self.assertEqual(values['request_checks'], values['put_checks'] + values['get_checks'])
        self.assertEqual(values['put_checks'], values['successful_put_checks'] + values['rejected_put_checks'])
        self.assertEqual(values['request_checks'], values['nonstreaming_checks'] + values['stream_open_checks'])
        self.assertEqual(5, values['approved_database_write_checks'])
        self.assertEqual(0, values['other_resync_checks'])
        self.assertNotIn('heartbeat_checks', values); self.assertNotIn('short_expiry_checks', values)

    def test_unmeasured_or_mismatched_prefix_never_loads_tokens_or_claims_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=Path(directory),
                require_measurements=Mock(), COUNTERS=(), EXPECTED_JAR_COUNTS=None)
            with patch.object(probe, 'load_tokens') as load, self.assertRaises(SystemExit): probe.run(ci)
            load.assert_not_called()
            self.assertEqual('incomplete', json.loads((Path(directory) / 'f27-packaged-result.txt').read_text())['status'])

    def test_response_failure_after_recorded_commit_still_stops_children_and_reobserves_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True); jar.write_bytes(b'not-a-real-jar')
            app, stub = Mock(), Mock(); events = []
            state = {str(i): [] for i in range(16)}
            def seeded(_ci):
                state.clear(); state.update(snapshot()); return owned()
            def request(_base, path, **kwargs):
                if path == '/api/v1/notes/notifications': return 200, json.dumps(notifications()).encode(), {'Cache-Control': 'no-store'}
                self.assertEqual('PUT', kwargs['method'])
                state['private_note_mailboxes'][0]['favorite'] = True
                return 200, b'unexpected response body after recorded commit', {'Cache-Control': 'no-store'}
            def cleaned(_ci, keys):
                self.assertEqual(probe.USERS, keys['users'])
                self.assertTrue(state['private_note_mailboxes'][0]['favorite'])
                events.append('cleanup-reobserved')
            class FakeStream:
                def __init__(self, *_): pass
                def wait(self, *_): pass
                def close(self): events.append('stream-close')
            ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=root, ROOT=root,
                require_measurements=Mock(), COUNTERS=probe.COUNTERS, EXPECTED_JAR_COUNTS=probe.EXPECTED_JAR_COUNTS,
                database_snapshot=lambda: copy.deepcopy(state), owned_sql=lambda *_: '0',
                f4=types.SimpleNamespace(start_ready_children=lambda *_: (app, stub, 'base', 'endpoint', 1, 0)),
                f3=types.SimpleNamespace(WRITER='writer', APP='app', expect=lambda *_a, **_k: dict(counts={}, requests=[], objects={}), request=request,
                    stop_owned_child=lambda child, _label: events.append('stop-app' if child is app else 'stop-stub') or 143))
            env = dict(GITHUB_SHA='a' * 40, F2_TEST_JDBC_URL='synthetic', F2_TEST_DB_USER='reader', F2_TEST_DB_PASSWORD='p1',
                       F3_TEST_WRITER_PASSWORD='p2', F3_TEST_JWT_SECRET=KEY)
            with patch.dict(os.environ, env), patch.object(probe, 'load_tokens', return_value=dict(F27_CALLER='unit-caller', F27_OTHER='unit-other')), \
                    patch.object(probe, 'Stream', FakeStream), patch.object(probe, 'seed', side_effect=seeded), patch.object(probe, 'cleanup', side_effect=cleaned):
                with self.assertRaisesRegex(AssertionError, 'empty original 200'): probe.run(ci)
            self.assertEqual(['stop-app', 'stream-close', 'stream-close', 'stop-stub', 'cleanup-reobserved'], events)
            result = json.loads((root / 'f27-packaged-result.txt').read_text())
            self.assertEqual('incomplete', result['status']); self.assertNotIn('owned_cleanup_restored', result)


if __name__ == '__main__':
    unittest.main()
