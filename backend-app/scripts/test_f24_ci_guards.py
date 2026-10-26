"""F24 guards: mocked resources.

Preparation only until separately executed. These controls do not prove PostgreSQL SQL,
HTTP/JAR behavior, transaction rollback, original JpaTM or independent business results.
"""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
import copy
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f24_ci as f24
from test_f17_ci_guards import HOSTED

OWNER = '123-1'
MOCK_COUNTS = dict(zip(f24.COUNTERS, (12, 2, 8, 5, 3, 1, 2, 1, 1, 2, 1, 2)))


def token(identity, expiry, role='ROLE_USER'):
    claims = base64.urlsafe_b64encode(json.dumps(
        {'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + claims + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=None, wire=None, startup=False, after_snapshot=False,
                     shutdown=None, remaining='0'):
    """Stateful control journey; payloads deliberately share expected_page, never an oracle."""
    root = Path(directory).resolve()
    evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
    jar.parent.mkdir(parents=True)
    jar.write_bytes(b'not-an-executable-jar')
    baseline = {'private_note_blocks': [], 'private_note_members': [], 'users': []}
    baseline.update({'protected_' + str(index): [{'sentinel': index}] for index in range(13)})
    database = copy.deepcopy(baseline)
    calls, events, observed_snapshots = [], [], []
    state = {'started': False, 'cleanup_result': None}
    statuses = {6: 401, 7: 403, 8: 403, 9: 503, 11: 503}
    messages = {6: '인증 토큰이 필요합니다.', 7: '접근할 수 없습니다.', 8: '접근할 수 없습니다.',
                9: f24.STORAGE, 11: f24.MEMBER_UNAVAILABLE}

    def snapshot():
        if after_snapshot and len(calls) == 5:
            raise AssertionError('mock post-request snapshot failed')
        value = copy.deepcopy(database)
        if startup and state['started']:
            value['protected_0'][0]['sentinel'] = 'startup mutation'
        observed_snapshots.append(copy.deepcopy(value))
        events.append('snapshot')
        return value

    def seed():
        events.append('seed')
        database['users'] = [{'user_id': identity, 'nickname': f24.NAMES[identity]} for identity in f24.USERS]
        database['private_note_members'] = [{'member_id': identity} for identity in f24.MEMBERS]
        database['private_note_blocks'] = [
            {'member_id': first, 'blocked_id': second, 'created_at': f24.CREATED} for first, second in f24.PAIRS]
        return f24.OWNED

    def cleanup(owned, created):
        events.append('cleanup')
        receipt = evidence / 'f24-packaged-result.txt'
        state['cleanup_result'] = receipt.read_text() if receipt.exists() else None
        database.clear()
        database.update(copy.deepcopy(baseline))

    def observe(owned):
        events.append('observe')
        if owned != f24.OWNED:
            raise AssertionError('Unclaimed seed passed to observation')
        return (f24.MISSING,) if any(row['member_id'] == f24.MISSING
                                    for row in database['private_note_members']) else ()

    def request(base, path, **kwargs):
        calls.append((path, copy.deepcopy(kwargs)))
        number = len(calls)
        events.append('request')
        if number == failure:
            raise AssertionError('mock request failure')
        if number == 2:
            database['private_note_blocks'] = [row for row in database['private_note_blocks']
                if (row['member_id'], row['blocked_id']) != (f24.CALLER, f24.OTHER)]
        if number == 5:
            database['private_note_members'].append({'member_id': f24.MISSING})
        if mutation == 'row' and number == 2:
            database['private_note_blocks'] = []  # reverse edge is protected
        if mutation == 'column' and number == 2:
            database['users'][0]['nickname'] = 'unapproved'
        if mutation == 'member' and number == 5:
            database['private_note_members'].append({'member_id': f24.MISSING + 1})
        status = statuses.get(number, 200)
        if number in (1, 3):
            body = json.dumps(f24.expected_page(number == 1)).encode()
        elif status == 200:
            body = b''
        else:
            value = {'message': messages[number]} if number in (9, 11) else {
                'code': status, 'message': messages[number], 'data': None}
            body = json.dumps(value).encode()
        headers = {'Cache-Control': f24.f19.CACHE_CONTROL if number in (6, 7, 8) else 'no-store'}
        if wire == 'cache' and number == 5:
            headers['Cache-Control'] = 'public'
        if wire == 'cookie' and number == 5:
            headers['Set-Cookie'] = 'forbidden'
        if wire == 'body' and number == 5:
            body = b'{}'
        return status, body, headers

    def storage(*args, **kwargs):
        bad = mutation == 's3' and len(calls) >= 2
        return {'counts': {'PUT': int(bad), 'DELETE': 0}, 'objects': [], 'requests': []}

    def start(*args):
        state['started'] = True
        events.append('start')
        return object(), object(), 'http://mock-app', 'http://mock-stub', 1, 0

    def sql(query):
        events.append(query.split()[0])
        return remaining if query.endswith("'pawbridge_f3_writer')") else '0'

    def stop(child, label):
        events.append('stop')
        return 0

    def load():
        events.append('token')
        return {'F24_CALLER': 'private-used-token'}

    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer',
               F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f24, 'ROOT', root),
                     patch.object(f24, 'EVIDENCE', evidence),
                     patch.object(f24, 'EXPECTED_JAR_COUNTS', MOCK_COUNTS),
                     patch.object(f24, 'require_fixture', return_value=OWNER),
                     patch.object(f24, 'require_owner', return_value=OWNER),
                     patch.object(f24, 'require_prior_receipts'), patch.object(f24.f21, 'check_receipt'),
                     patch.object(f24, 'database_snapshot', side_effect=snapshot),
                     patch.object(f24.f3, 'request', side_effect=request), patch.object(f24.f3, 'expect', side_effect=storage),
                     patch.object(f24.f4, 'fresh_log', return_value=shutdown if shutdown is not None else
                                  'Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql_mock = stack.enter_context(patch.object(f24.f3, 'sql', side_effect=sql))
        seed_mock = stack.enter_context(patch.object(f24, 'prepare_business_rows', side_effect=seed))
        start_mock = stack.enter_context(patch.object(f24.f4, 'start_ready_children', side_effect=start))
        stop_mock = stack.enter_context(patch.object(f24.f3, 'stop_owned_child', side_effect=stop))
        observe_mock = stack.enter_context(patch.object(f24, 'observe_created_members', side_effect=observe))
        cleanup_mock = stack.enter_context(patch.object(f24, 'cleanup_business_rows', side_effect=cleanup))
        load_mock = stack.enter_context(patch.object(f24, 'load_tokens', side_effect=load))
        yield {'evidence': evidence, 'requests': calls, 'events': events, 'snapshots': observed_snapshots,
               'database': database, 'baseline': baseline, 'state': state, 'sql': sql_mock, 'seed': seed_mock,
               'start': start_mock, 'stop': stop_mock, 'observe': observe_mock, 'cleanup': cleanup_mock, 'load': load_mock}


class F24GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_entering_public_actions__then_refuse_before_sql_or_children(self):
        actions = (f24.prepare, f24.require_owner, f24.require_fixture, f24.prepare_business_rows,
                   lambda: f24.cleanup_business_rows(f24.OWNED, ()), f24.load_tokens,
                   f24.require_measurements, f24.smoke,
                   lambda: f24.require_prior_receipts(OWNER))
        for action in actions:
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f24.f3, 'sql') as sql, patch.object(f24.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                start.assert_not_called()

    def test_given_owned_sql_failure__when_reporting__then_hide_command_sql_stdout_and_stderr(self):
        failure = subprocess.CalledProcessError(1, ['private-command'], output='private-output', stderr='private-stderr')
        for arguments in ({'query': 'SELECT private_sql'}, {'input_text': 'private_batch'}):
            with patch.object(f24.f3, 'sql', side_effect=failure):
                with self.assertRaises(SystemExit) as caught:
                    f24.owned_sql(**arguments)
            for private in ('private-command', 'private-output', 'private-stderr', 'private_sql', 'private_batch'):
                self.assertNotIn(private, str(caught.exception))

    def test_given_restoration_identity__when_checking_owner_and_receipts__then_no_old_writer_zero_checker(self):
        events = []
        with patch.object(f24.f19, 'require_identity', side_effect=lambda owner: events.append(('identity', owner))), \
                patch.object(f24.f21, 'require_f21_receipt', side_effect=lambda owner: events.append(('f21', owner))), \
                patch.object(f24.f21, 'check_receipt', side_effect=lambda path, proof: events.append((path.name, proof))), \
                patch.object(f24.f23, 'require_fixture', side_effect=AssertionError('old strict forbidden')):
            f24.require_prior_receipts(OWNER)
        self.assertEqual(['identity', 'f20-fixture-reuse.txt', 'f21', 'f22-fixture-reuse.txt', 'f23-fixture-reuse.txt'],
                         [event[0] for event in events])
        self.assertEqual(OWNER, events[0][1])
        with patch.object(f24.f22, 'require_owner', return_value=OWNER) as owner, \
                patch.object(f24.f21, 'check_receipt') as receipt:
            self.assertEqual(OWNER, f24.require_owner())
            owner.assert_called_once()
            self.assertEqual(['f23-fixture-reuse.txt', 'f24-fixture-write.txt'],
                             [call.args[0].name for call in receipt.call_args_list])
            self.assertEqual(f24.fixture_proof(OWNER), receipt.call_args.args[1])

    def test_given_previous_strict_and_matching_receipt__when_preparing__then_one_atomic_grant_before_new_strict_without_row_changes(self):
        events = []
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve()
            (evidence / 'f23-fixture-reuse.txt').write_text(f24.f23.fixture_proof(OWNER))
            def strict():
                self.assertEqual(f24.fixture_proof(OWNER), (evidence / 'f24-fixture-write.txt').read_text())
                events.append('new-strict')
                return OWNER
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'EVIDENCE', evidence), \
                    patch.object(f24.f23, 'require_fixture', side_effect=lambda: events.append('old-strict') or OWNER), \
                    patch.object(f24, 'database_snapshot', side_effect=lambda: events.append('snapshot') or {}), \
                    patch.object(f24, 'require_fixture', side_effect=strict), \
                    patch.object(f24, 'owned_sql', side_effect=lambda **kwargs: events.append('grant')) as sql:
                f24.prepare()
            statement = sql.call_args.kwargs['input_text']
            self.assertTrue(statement.startswith('BEGIN;\n'))
            self.assertTrue(statement.endswith('COMMIT;\n'))
            self.assertEqual(2, statement.count('GRANT '))
            self.assertIn('SELECT(member_id),INSERT(member_id),UPDATE(member_id)', statement)
            self.assertIn('SELECT(member_id,blocked_id),DELETE', statement)
            for forbidden in ('CREATE ', 'ALTER ', 'SEQUENCE', 'INSERT INTO ', 'UPDATE pawbridge', 'DELETE FROM '):
                self.assertNotIn(forbidden, statement)
        self.assertEqual(['old-strict', 'snapshot', 'grant', 'new-strict', 'snapshot'], events)

    def test_given_wrong_previous_fixture_receipt_or_snapshot_failure__when_preparing__then_no_grant_or_proof_write(self):
        for phase in ('fixture', 'prior-receipt', 'own-receipt', 'snapshot'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                evidence = Path(directory).resolve()
                (evidence / 'f23-fixture-reuse.txt').write_text(
                    'foreign' if phase == 'prior-receipt' else f24.f23.fixture_proof(OWNER))
                target = evidence / 'f24-fixture-write.txt'
                if phase == 'own-receipt':
                    target.write_text('preserve-foreign-owner')
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'EVIDENCE', evidence), \
                        patch.object(f24.f23, 'require_fixture', side_effect=SystemExit('owner') if phase == 'fixture' else None,
                                     return_value=OWNER), \
                        patch.object(f24, 'database_snapshot', side_effect=SystemExit('snapshot') if phase == 'snapshot' else None), \
                        patch.object(f24, 'owned_sql') as sql, patch.object(Path, 'write_text') as write:
                    with self.assertRaises(SystemExit):
                        f24.prepare()
                    sql.assert_not_called()
                    write.assert_not_called()
                if phase == 'own-receipt':
                    self.assertEqual('preserve-foreign-owner', target.read_text())

    def test_given_atomic_grant_failure__when_preparing__then_no_owned_proof_or_post_grant_strict_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve()
            (evidence / 'f23-fixture-reuse.txt').write_text(f24.f23.fixture_proof(OWNER))
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'EVIDENCE', evidence), \
                    patch.object(f24.f23, 'require_fixture', return_value=OWNER), \
                    patch.object(f24, 'database_snapshot', return_value={}), \
                    patch.object(f24, 'owned_sql', side_effect=SystemExit('grant batch failed')) as sql, \
                    patch.object(f24, 'require_fixture') as strict:
                with self.assertRaisesRegex(SystemExit, 'grant batch failed'):
                    f24.prepare()
                sql.assert_called_once()
                strict.assert_not_called()
            self.assertFalse((evidence / 'f24-fixture-write.txt').exists())

    def test_given_five_inherited_tables__when_checking_catalog__then_exact_shape_acl_constraints_and_no_extra_writer_columns(self):
        queries = []
        with patch.object(f24, 'require_owner', return_value=OWNER), \
                patch.object(f24, 'catalog', side_effect=lambda query, stage: queries.append((query, stage)) or 't'):
            self.assertEqual(OWNER, f24.require_fixture())
        self.assertEqual(['shape', 'acl', 'constraints'] * 5, [stage for _, stage in queries])
        self.assertEqual(5, len(f24.f20.TABLES) + len(f24.f22.TABLES))
        for query, stage in queries:
            self.assertTrue(query.startswith('SELECT '))
            if stage == 'shape':
                for required in ("is_identity='NO'", "is_generated='NEVER'", "pg_get_userbyid(relowner)='postgres'"):
                    self.assertIn(required, query)
            if stage == 'acl':
                self.assertIn('pg_get_serial_sequence(', query)
                self.assertIn('IS NULL', query)
                self.assertIn('pawbridge_f2_reader', query)
                self.assertIn('pawbridge_f3_writer', query)
        member_acl, block_acl = queries[4][0], queries[7][0]
        self.assertIn(",column_name,'SELECT')=(column_name='member_id')", member_acl)
        for privilege in ('INSERT', 'UPDATE'):
            self.assertIn(",column_name,'" + privilege + "')=(column_name='member_id')", member_acl)
            self.assertIn(",column_name,'" + privilege + "')=(false)", block_acl)
        self.assertIn(",column_name,'SELECT')=(column_name IN ('member_id','blocked_id'))", block_acl)
        self.assertIn("'DELETE')=true", block_acl)
        self.assertIn("'DELETE')=false", member_acl)
        for required in ("contype='p'", 'convalidated', 'NOT condeferrable', "confdeltype='c'",
                         "member_id<>blocked_id", 'idx_private_note_blocked_member', "a.amname='btree'"):
            self.assertIn(required, queries[8][0])
        self.assertIn('member_id>0', queries[5][0])
        self.assertIn("confrelid='pawbridge_user.users'::regclass", queries[2][0])
        self.assertIn("'reply_to','uuid','YES'", queries[9][0])
        for index, readable in ((10, ('note_id', 'sender_id', 'recipient_id', 'body', 'context_type',
                                     'context_id', 'created_at', 'expires_at')),
                                (13, ('member_id', 'note_id', 'direction', 'read_at', 'favorite', 'created_at'))):
            self.assertIn("column_name IN (" + ','.join("'" + value + "'" for value in readable) + ')', queries[index][0])
            self.assertIn(",column_name,'SELECT,INSERT,UPDATE,REFERENCES')", queries[index][0])

    def test_given_any_false_shape_acl_or_constraint_result__when_checking_fixture__then_stop_at_that_catalog_boundary(self):
        for position in range(15):
            with self.subTest(position=position), patch.object(f24, 'require_owner', return_value=OWNER), \
                    patch.object(f24, 'catalog', side_effect=['t'] * position + ['f']) as catalog:
                with self.assertRaises(SystemExit):
                    f24.require_fixture()
                self.assertEqual(position + 1, catalog.call_count)

    def test_given_non_select_or_unknown_catalog_stage_or_short_snapshot__when_checking__then_refuse(self):
        with patch.object(f24, 'owned_sql') as sql:
            for query, stage in (('DELETE FROM private_note_blocks', 'acl'), ('SELECT true', 'business')):
                with self.assertRaises(ValueError):
                    f24.catalog(query, stage)
            sql.assert_not_called()
            f24.catalog('SELECT true', 'shape')
            sql.assert_called_once_with('SELECT true')
        with patch.object(f24.f23, 'database_snapshot', return_value={str(i): [] for i in range(15)}):
            with self.assertRaisesRegex(SystemExit, 'sixteen'):
                f24.database_snapshot()
        expected = {str(i): [] for i in range(16)}
        with patch.object(f24.f23, 'database_snapshot', return_value=expected):
            self.assertIs(expected, f24.database_snapshot())

    def test_given_owned_revocation_and_body_failure__when_unwinding__then_identity_and_receipts_precede_restore_and_new_strict(self):
        for kind, clause, role in (
                ('delete', 'DELETE ON ' + f24.WRITER_BLOCKS, 'pawbridge_f3_writer'),
                ('member-update', 'UPDATE(member_id) ON ' + f24.WRITER_MEMBERS, 'pawbridge_f3_writer'),
                ('nickname', 'SELECT(nickname) ON pawbridge_user.users', 'pawbridge_f2_reader')):
            events = []
            with self.subTest(kind=kind), patch.object(f24, 'require_fixture', side_effect=lambda: events.append('strict') or OWNER), \
                    patch.object(f24, 'require_prior_receipts', side_effect=lambda owner: events.append(('identity-receipts', owner))), \
                    patch.object(f24.f21, 'check_receipt', side_effect=lambda path, proof: events.append((path.name, proof))), \
                    patch.object(f24, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(AssertionError, 'body'):
                    with f24.denied_privilege(kind):
                        raise AssertionError('body')
            self.assertEqual(['strict', 'REVOKE ' + clause + ' FROM ' + role, ('identity-receipts', OWNER),
                              ('f24-fixture-write.txt', f24.fixture_proof(OWNER)), 'GRANT ' + clause + ' TO ' + role, 'strict'], events)

    def test_given_foreign_identity_or_changed_own_receipt_under_denial__when_unwinding__then_no_grant(self):
        for phase in ('identity', 'receipt'):
            with self.subTest(phase=phase), patch.object(f24, 'require_fixture', return_value=OWNER) as strict, \
                    patch.object(f24, 'require_prior_receipts', side_effect=SystemExit('foreign') if phase == 'identity' else None), \
                    patch.object(f24.f21, 'check_receipt', side_effect=SystemExit('foreign') if phase == 'receipt' else None), \
                    patch.object(f24, 'owned_sql') as sql:
                with self.assertRaisesRegex(SystemExit, 'foreign'):
                    with f24.denied_privilege('delete'):
                        pass
                strict.assert_called_once()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_invalid_denial_or_failed_restore__when_unwinding__then_no_unowned_grant_or_post_restore_strict(self):
        with patch.object(f24, 'require_fixture', return_value=OWNER), patch.object(f24, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f24.denied_privilege('all-columns'):
                    pass
            sql.assert_not_called()
        with patch.object(f24, 'require_fixture', return_value=OWNER) as strict, \
                patch.object(f24, 'require_prior_receipts'), patch.object(f24.f21, 'check_receipt'), \
                patch.object(f24, 'owned_sql', side_effect=['', SystemExit('restore failed')]):
            with self.assertRaisesRegex(SystemExit, 'restore failed'):
                with f24.denied_privilege('delete'):
                    pass
            strict.assert_called_once()

    def test_given_unmeasured_journey_counts__when_smoke_requested__then_refuse_before_files_or_resources(self):
        for counts in (dict(MOCK_COUNTS, request_checks=-1), dict(MOCK_COUNTS, request_checks=True), {'foreign': 1}):
            with self.subTest(counts=counts), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f24, 'EXPECTED_JAR_COUNTS', counts), patch.object(f24, 'require_fixture') as strict, \
                    patch.object(Path, 'write_text') as write, patch.object(f24.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'unmeasured'):
                    f24.smoke()
                strict.assert_not_called()
                write.assert_not_called()
                start.assert_not_called()

    def test_given_workflow__when_staging_f24__then_f23_finishes_before_grants_smoke_and_always_owned_cleanup(self):
        workflow = (f24.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F24 owned writer, rollback, recovery and selected original guards without resources')
        self.assertLess(workflow.index('python3 scripts/f23_ci.py prepare'), boundary)
        stage = workflow[boundary:]
        ordered = ('scripts/test_f24_ci_guards.py', 'python3 scripts/f24_ci.py prepare')
        for first, second in zip(ordered, ordered[1:]):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertNotIn('noteBlockRemoveCompatibility', workflow)
        self.assertNotIn('python3 scripts/f24_ci.py results', workflow)
        cleanup = stage.index('- name: Remove only the owned disposable fixture')
        self.assertIn('if: always()', stage[cleanup:cleanup + 180])
        self.assertIn('run: python3 scripts/f3_ci.py cleanup', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-9]|2[0-3])_ci\.py (?:prepare|smoke)')
        self.assertIn('f24-helper-refusal.txt', workflow)
        source_start = workflow.index('- name: Require F24 to reuse the immutable F23 original-source Git object')
        source_end = workflow.index('\n      - name:', source_start)
        source_step = workflow[source_start:source_end]
        self.assertIn("git cat-file -e '" + f24.SOURCE + "^{commit}'", source_step)
        for forbidden in ('git fetch', 'git config ', 'git checkout', '--force', 'GIT_CONFIG_COUNT='):
            self.assertNotIn(forbidden, source_step)
        self.assertIn("grep -F 'restricted to this repository on GitHub-hosted Linux' build/ci/f24-helper-refusal.txt", workflow)
        self.assertIn('sparse-checkout:', workflow)
        self.assertNotIn('            community-service\n', workflow)
        denied = next(line for line in workflow.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/com/pawbridge/backend/compatibility/F24Input.class'))
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/com/pawbridge/communityservice/contact/F24LegacyNoteBlockRemoveService.class'))

    def test_given_used_token_and_old_keys__when_loading__then_only_owned_regular_0600_identity_role_and_ttl_without_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            path = root / ('f3-tokens-' + OWNER + '.json')
            used = token(f24.CALLER, 1060)
            original = json.dumps({'F24_CALLER': used, 'F14_OTHER': 'expired-unused', 'expired': 'old-unused'})
            path.write_text(original)
            path.chmod(0o600)
            env = dict(HOSTED, F3_TEST_TOKENS_PATH=str(path), RUNNER_TEMP=str(root))
            with patch.dict(os.environ, env, clear=True), patch.object(f24.time, 'time', return_value=1000):
                self.assertEqual({'F24_CALLER': used}, f24.load_tokens())
                self.assertEqual(original, path.read_text())
                for claims in ((f24.OTHER, 2000, 'ROLE_USER'), (True, 2000, 'ROLE_USER'),
                               (f24.CALLER, 2000, 'ROLE_ADMIN'), (f24.CALLER, 1059.999, 'ROLE_USER'),
                               (f24.CALLER, True, 'ROLE_USER'), (f24.CALLER, float('inf'), 'ROLE_USER')):
                    path.write_text(json.dumps({'F24_CALLER': token(*claims)}))
                    with self.assertRaises(SystemExit):
                        f24.load_tokens()
                path.write_text(original)
                path.chmod(0o644)
                with self.assertRaisesRegex(SystemExit, '0600'):
                    f24.load_tokens()
                path.chmod(0o600)
                alias = root / 'redirected.json'
                alias.symlink_to(path)
                with patch.dict(os.environ, {'F3_TEST_TOKENS_PATH': str(alias)}):
                    with self.assertRaisesRegex(SystemExit, 'owned'):
                        f24.load_tokens()
                foreign = root / 'foreign-owner.json'
                foreign.write_text(original)
                foreign.chmod(0o600)
                with patch.dict(os.environ, {'F3_TEST_TOKENS_PATH': str(foreign)}):
                    with self.assertRaisesRegex(SystemExit, 'owned'):
                        f24.load_tokens()

    def test_given_reserved_key_collision_or_absence__when_seeding__then_no_existing_row_change_and_one_atomic_exact_seed(self):
        with patch.object(f24, 'require_fixture'), patch.object(f24, 'owned_sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'must all be absent'):
                f24.prepare_business_rows()
            sql.assert_called_once()
            absence = sql.call_args.args[0]
            for table in ('pawbridge_user.users', 'contact_deletions', f24.WRITER_MEMBERS):
                self.assertIn(table, absence)
            self.assertIn(str(f24.MISSING), absence)
        with patch.object(f24, 'require_fixture'), patch.object(f24, 'owned_sql', side_effect=['0', '']) as sql:
            self.assertEqual(f24.OWNED, f24.prepare_business_rows())
        statement = sql.call_args.args[0]
        self.assertTrue(statement.startswith('BEGIN;'))
        self.assertTrue(statement.endswith('COMMIT;'))
        self.assertEqual(3, statement.count('INSERT INTO '))
        for identity in (*f24.USERS,):
            self.assertIn('f24-' + str(identity) + '@fixture.invalid', statement)
        for first, second in f24.PAIRS:
            self.assertIn('(' + str(first) + ',' + str(second) + ',', statement)
        for forbidden in ('CREATE ', 'UPDATE ', 'DELETE ', 'SEQUENCE', '(' + str(f24.MISSING) + ')'):
            self.assertNotIn(forbidden, statement)

    def test_given_exact_owned_seed_and_observed_member__when_cleaning__then_unknown_references_refused_and_fk_order_keeps_other_keys(self):
        for created in ((), (f24.MISSING,)):
            events = []
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'require_owner', side_effect=lambda: events.append('owner')), \
                    patch.object(f24, 'owned_sql', side_effect=lambda query: events.append(query) or '0'):
                f24.cleanup_business_rows(f24.OWNED, created)
            statements = [event for event in events if event != 'owner']
            self.assertEqual(5, len(statements))
            self.assertIn('private_notes', statements[0])
            self.assertIn('private_note_mailboxes', statements[0])
            self.assertIn('NOT IN', statements[1])
            deletes = statements[2:]
            for query, table in zip(deletes, (f24.WRITER_BLOCKS, f24.WRITER_MEMBERS, 'pawbridge_user.users')):
                self.assertTrue(query.startswith('DELETE FROM ' + table + ' WHERE '))
                self.assertEqual('owner', events[events.index(query) - 1])
            self.assertIn('(user_id,email)', deletes[-1])
            self.assertNotIn(str(f24.MISSING), deletes[-1])
            self.assertEqual(bool(created), str(f24.MISSING) in deletes[1])
        for responses in (['1'], ['0', '1']):
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'require_owner'), \
                    patch.object(f24, 'owned_sql', side_effect=responses) as sql:
                with self.assertRaises(SystemExit):
                    f24.cleanup_business_rows(f24.OWNED, (f24.MISSING,))
                self.assertFalse(any(call.args[0].startswith('DELETE ') for call in sql.call_args_list))
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f24, 'owned_sql') as sql:
            for owned, created in (((), ()), (f24.OWNED, (f24.OTHER,))):
                with self.assertRaises(ValueError):
                    f24.cleanup_business_rows(owned, created)
            sql.assert_not_called()

    def test_given_reserved_missing_member__when_observing_after_seed__then_owner_and_exact_zero_or_one_before_cleanup(self):
        with patch.object(f24, 'require_owner') as owner, patch.object(f24, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                f24.observe_created_members(())
            owner.assert_not_called()
            sql.assert_not_called()
            for value, expected in (('0', ()), ('1', (f24.MISSING,))):
                sql.return_value = value
                self.assertEqual(expected, f24.observe_created_members(f24.OWNED))
                self.assertEqual('SELECT count(*) FROM ' + f24.WRITER_MEMBERS + ' WHERE member_id=' + str(f24.MISSING),
                                 sql.call_args.args[0])
            sql.return_value = '2'
            with self.assertRaises(SystemExit):
                f24.observe_created_members(f24.OWNED)
        with patch.object(f24, 'require_owner', side_effect=SystemExit('foreign owner')), patch.object(f24, 'owned_sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'foreign owner'):
                f24.observe_created_members(f24.OWNED)
            sql.assert_not_called()

    def test_given_mocked_exact_twelve_request_journey__when_smoking__then_two_approved_deltas_and_complete_cleanup_before_pass(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as h:
            f24.smoke()
            receipt = json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())
            self.assertEqual(MOCK_COUNTS, {key: receipt[key] for key in f24.COUNTERS})
            self.assertEqual(('passed', 1, 0), (receipt['status'], receipt['shutdown_pools'], receipt['remaining_application_connections']))
            methods = [kwargs['method'] for _, kwargs in h['requests']]
            self.assertEqual((12, 8, 3, 1), (len(methods), methods.count('DELETE'), methods.count('GET'), methods.count('PUT')))
            h['cleanup'].assert_called_once_with(f24.OWNED, (f24.MISSING,))
            h['observe'].assert_called_once_with(f24.OWNED)
            self.assertEqual(2, h['stop'].call_count)
            self.assertEqual(['stop', 'stop', 'observe', 'cleanup'], [event for event in h['events'] if event in ('stop', 'observe', 'cleanup')])
            self.assertIsNone(h['state']['cleanup_result'])
            self.assertEqual(h['baseline'], h['database'])
            self.assertEqual(12, h['load'].call_count)  # mask once + eleven authenticated requests; missing-token request reloads zero
            self.assertNotIn('Authorization', h['requests'][5][1]['headers'])
            self.assertEqual('/api/v1/notes/stream/extra', h['requests'][6][0])
            self.assertEqual('/api/v1/notes/blocks/' + str(f24.OTHER) + '/extra', h['requests'][7][0])
            for index, (_, kwargs) in enumerate(h['requests']):
                self.assertEqual(str(f24.OTHER), kwargs['headers']['X-User-Id'])
                if index != 5:
                    self.assertEqual('Bearer private-used-token', kwargs['headers']['Authorization'])
            for index, event in enumerate(h['events']):
                if event == 'request' and h['events'][index - 1] == 'token':
                    self.assertEqual('snapshot', h['events'][index - 2])
            seeded = next(value for value in h['snapshots'] if len(value['private_note_blocks']) == 2)
            self.assertEqual(2, len(seeded['private_note_blocks']))  # expected deepcopy never rewrites captured before-state
            self.assertTrue(any(value['private_note_blocks'] == [{'member_id': f24.OTHER, 'blocked_id': f24.CALLER,
                                                                 'created_at': f24.CREATED}] for value in h['snapshots']))

    def test_given_token_stales_during_before_snapshot__when_smoking__then_no_http_but_children_stop_and_seed_cleanup(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as h:
            def load():
                if 'seed' in h['events']:
                    self.assertEqual('snapshot', h['events'][-1])
                    h['events'].append('stale')
                    raise SystemExit('used token stale')
                h['events'].append('mask-token')
                return {'F24_CALLER': 'private-used-token'}
            h['load'].side_effect = load
            with self.assertRaisesRegex(SystemExit, 'stale'):
                f24.smoke()
            self.assertEqual([], h['requests'])
            self.assertEqual(2, h['stop'].call_count)
            h['cleanup'].assert_called_once_with(f24.OWNED, ())
            self.assertEqual('incomplete', json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())['status'])

    def test_given_startup_or_unapproved_row_column_member_or_s3_change__when_smoking__then_fail_closed_and_cleanup_observed_member(self):
        for options in ({'startup': True}, {'mutation': 'row'}, {'mutation': 'column'}, {'mutation': 'member'}, {'mutation': 's3'}):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as h:
                with self.assertRaises(AssertionError):
                    f24.smoke()
                self.assertEqual(2, h['stop'].call_count)
                if h['seed'].called:
                    created = (f24.MISSING,) if len(h['requests']) >= 5 else ()
                    h['cleanup'].assert_called_once_with(f24.OWNED, created)
                else:
                    h['cleanup'].assert_not_called()
                self.assertEqual('incomplete', json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())['status'])

    def test_given_committed_missing_member_but_response_or_snapshot_failure__when_unwinding__then_observe_and_cleanup_even_before_success_counters(self):
        for options in ({'wire': 'cache'}, {'wire': 'cookie'}, {'wire': 'body'}, {'after_snapshot': True}):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as h:
                with self.assertRaises(AssertionError):
                    f24.smoke()
                self.assertEqual(5, len(h['requests']))
                h['observe'].assert_called_once_with(f24.OWNED)
                h['cleanup'].assert_called_once_with(f24.OWNED, (f24.MISSING,))
                self.assertEqual(2, h['stop'].call_count)
                receipt = json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())
                self.assertEqual(('incomplete', 0), (receipt['status'], receipt['missing_target_checks']))
                self.assertEqual(h['baseline'], h['database'])

    def test_given_request_failure_during_denial_or_failed_grant__when_unwinding__then_restore_attempt_and_two_child_cleanup_without_pass(self):
        for phase in ('request', 'restore'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, \
                    packaged_harness(directory, failure=9 if phase == 'request' else None) as h:
                original = h['sql'].side_effect
                def sql(query):
                    if phase == 'restore' and query.startswith('GRANT DELETE '):
                        raise SystemExit('restore failed')
                    return original(query)
                h['sql'].side_effect = sql
                with self.assertRaises((AssertionError, SystemExit)):
                    f24.smoke()
                statements = [call.args[0] for call in h['sql'].call_args_list]
                self.assertTrue(any(query.startswith('REVOKE DELETE ') for query in statements))
                self.assertTrue(any(query.startswith('GRANT DELETE ') for query in statements))
                self.assertEqual(2, h['stop'].call_count)
                h['cleanup'].assert_called_once_with(f24.OWNED, (f24.MISSING,))
                self.assertEqual('incomplete', json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())['status'])

    def test_given_failed_child_seed_observation_or_cleanup__when_unwinding__then_never_delete_unclaimed_keys_or_mark_passed(self):
        for phase in ('seed', 'app-stop', 'observation', 'cleanup'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as h:
                if phase == 'app-stop':
                    h['stop'].side_effect = [SystemExit('owned boundary failed'), 0]
                else:
                    target = {'seed': h['seed'], 'observation': h['observe'], 'cleanup': h['cleanup']}[phase]
                    target.side_effect = SystemExit('owned boundary failed')
                with self.assertRaisesRegex(SystemExit, 'owned boundary failed'):
                    f24.smoke()
                self.assertEqual(2, h['stop'].call_count)
                if phase in ('seed', 'observation'):
                    h['cleanup'].assert_not_called()
                if phase == 'seed':
                    h['observe'].assert_not_called()
                    self.assertEqual([], h['requests'])
                self.assertEqual('incomplete', json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())['status'])

    def test_given_missing_graceful_shutdown_connection_or_private_log_leak__when_finishing__then_no_passed_receipt(self):
        for options in ({'shutdown': 'Graceful shutdown complete'}, {'remaining': '1'},
                        {'shutdown': 'Shutdown completed. Shutdown completed. Graceful shutdown complete private-reader'}):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as h:
                with self.assertRaises(AssertionError):
                    f24.smoke()
                self.assertEqual(2, h['stop'].call_count)
                h['cleanup'].assert_called_once_with(f24.OWNED, (f24.MISSING,))
                self.assertEqual('incomplete', json.loads((h['evidence'] / 'f24-packaged-result.txt').read_text())['status'])


if __name__ == '__main__':
    print('F24 guards: mocked resources and pinned read-only Git inputs; no actual PG/JAR/JVM/socket evidence.', flush=True)
    with redirect_stdout(io.StringIO()):
        unittest.main()
