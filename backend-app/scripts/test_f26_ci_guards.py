"""F26 fixture controls with mocked SQL and subprocess boundaries.

Pinned input loading uses read-only Git objects, not working-tree paths. These guards do not prove
PostgreSQL syntax/ACL/rollback or SSE/JAR behavior; the packaged smoke is guarded separately.
"""
from contextlib import ExitStack, contextmanager, redirect_stdout
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f26_ci as f26

HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
OWNER = '123-1'


@contextmanager
def preparation(directory, events=None):
    root = Path(directory).resolve()
    evidence = root / 'build/ci'
    evidence.mkdir(parents=True)
    events = events if events is not None else []
    def sql(query='', **kwargs):
        events.append(kwargs.get('input_text', query))
        return 't'
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, HOSTED, clear=True), patch.object(f26, 'ROOT', root),
                patch.object(f26, 'EVIDENCE', evidence),
                patch.object(f26.f25, 'require_fixture', side_effect=lambda: events.append('old-full') or OWNER),
                patch.object(f26.subprocess, 'check_output', side_effect=AssertionError('no Git read')),
                patch.object(f26, 'database_snapshot', side_effect=lambda: events.append('snapshot') or {'protected': []}),
                patch.object(f26, 'require_fixture', side_effect=lambda: events.append('new-full') or OWNER),
                patch.object(f26, 'owned_sql', side_effect=sql)):
            stack.enter_context(item)
        yield root, evidence, events


class F26FixtureGuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_fixture_actions__then_refuse_before_sql_git_or_prior_stage(self):
        actions = (f26.require_owner, f26.require_fixture, f26.prepare,
                   f26.database_snapshot, f26.require_measurements, lambda: f26.owned_sql('SELECT true'), lambda: f26.require_prior_receipts(OWNER))
        for action in actions:
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f26.f3, 'sql') as sql, patch.object(f26.subprocess, 'check_output') as git, \
                    patch.object(f26.f25, 'require_owner') as owner, patch.object(f26.f25, 'require_fixture') as strict:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called(); git.assert_not_called(); owner.assert_not_called(); strict.assert_not_called()

    def test_given_failed_owned_sql__when_reporting__then_no_command_sql_stderr_or_output_is_exposed(self):
        command = ['private-command', 'private-sql', 'private-body', 'private-email@fixture.invalid']
        failures = (
            subprocess.CalledProcessError(1, command, output='private-output', stderr='private-stderr'),
            subprocess.TimeoutExpired(command, 30, output='private-output', stderr='private-stderr'),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26.f3, 'sql', side_effect=failure):
                    with self.assertRaises(SystemExit) as actual:
                        f26.owned_sql('private-sql', input_text='private-body private-email@fixture.invalid')
                self.assertIs(type(actual.exception), SystemExit)
                self.assertEqual('F26 owned fixture SQL failed without exposing command, SQL or output', str(actual.exception))
                self.assertNotRegex(str(actual.exception), 'private-|CalledProcessError|TimeoutExpired')
                self.assertIsNone(actual.exception.__cause__)
                self.assertTrue(actual.exception.__suppress_context__)

    def test_given_extended_writer__when_checking_owner__then_keep_acl_safe_f25_owner_and_exact_new_receipt_without_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26.f25, 'require_owner', return_value=OWNER) as owner, \
                patch.object(f26.f25, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f26.f21, 'check_receipt') as receipt:
            self.assertEqual(OWNER, f26.require_owner())
        owner.assert_called_once()
        receipt.assert_called_once_with(f26.EVIDENCE / 'f26-fixture-write.txt', f26.fixture_proof(OWNER))

    def test_given_revoked_user_or_writer_acl__when_checking_prior_receipts__then_use_marker_identity_without_owner_or_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26.f25, 'require_prior_receipts') as prior, \
                patch.object(f26.f25, 'require_owner', side_effect=AssertionError('User strict')), \
                patch.object(f26.f25, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f26.f21, 'check_receipt') as receipt:
            f26.require_prior_receipts(OWNER)
        prior.assert_called_once_with(OWNER)
        receipt.assert_called_once_with(f26.EVIDENCE / 'f25-fixture-write.txt', f26.f25.fixture_proof(OWNER))

    def test_given_missing_foreign_or_redirected_new_receipt__when_checking_owner__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve(); target = evidence / 'f26-fixture-write.txt'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26, 'EVIDENCE', evidence), \
                    patch.object(f26.f25, 'require_owner', return_value=OWNER):
                with self.assertRaises(SystemExit): f26.require_owner()
                target.write_text(f26.fixture_proof('foreign-owner'))
                with self.assertRaises(SystemExit): f26.require_owner()
                target.unlink(); elsewhere = evidence / 'elsewhere'; elsewhere.write_text(f26.fixture_proof(OWNER)); target.symlink_to(elsewhere)
                with self.assertRaises(SystemExit): f26.require_owner()

    def test_given_five_inherited_tables__when_checking_catalog__then_enforce_exact_old_acl_and_only_new_read_mutation_columns(self):
        queries = []
        with patch.object(f26, 'require_owner', return_value=OWNER), \
                patch.object(f26, 'catalog', side_effect=lambda query, stage: queries.append((query, stage)) or 't'), \
                patch.object(f26.f25, 'require_fixture', side_effect=AssertionError('old strict')):
            self.assertEqual(OWNER, f26.require_fixture())
        self.assertEqual(['shape', 'acl', 'constraints'] * 5, [stage for _, stage in queries])
        notes, mailboxes = queries[10][0], queries[13][0]
        self.assertIn("column_name,'SELECT')=(column_name IN ('note_id','expires_at'))", notes)
        self.assertIn("column_name,'UPDATE')=(column_name='note_id')", notes)
        self.assertIn("column_name,'SELECT')=(column_name IN ('member_id','note_id','direction','read_at'))", mailboxes)
        self.assertIn("column_name,'UPDATE')=(column_name='read_at')", mailboxes)
        for query in (notes, mailboxes):
            self.assertIn("column_name,'INSERT,REFERENCES')", query)
            self.assertIn("'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", query)
            self.assertIn('pg_get_serial_sequence', query)
        self.assertIn("column_name,'SELECT')=(false)", queries[4][0])  # reader members stays zero.
        self.assertIn("column_name,'UPDATE')=(column_name='member_id')", queries[4][0])
        self.assertIn("column_name,'INSERT')=(column_name IN ('member_id','blocked_id','created_at'))", queries[7][0])
        for token in ('convalidated', 'NOT condeferrable', 'confdeltype', 'member_id<>blocked_id', 'idx_private_note_blocked_member'):
            self.assertIn(token, queries[8][0])

    def test_given_rejected_shape_extra_acl_constraint_or_sequence_catalog_result__when_checking_fixture__then_stop_at_that_boundary(self):
        # Catalog booleans are controlled inputs, not an emulation of PostgreSQL privilege evaluation.
        for index in range(15):
            with self.subTest(index=index), patch.object(f26, 'require_owner', return_value=OWNER), \
                    patch.object(f26, 'catalog', side_effect=['t'] * index + ['f']) as catalog:
                with self.assertRaises(SystemExit): f26.require_fixture()
                self.assertEqual(index + 1, catalog.call_count)

    def test_given_non_catalog_statement_or_incomplete_snapshot__when_checking__then_refuse_without_sql_or_partial_snapshot(self):
        with patch.object(f26, 'owned_sql') as sql:
            for query, stage in (('DELETE FROM private_notes', 'acl'), ('SELECT true', 'business')):
                with self.assertRaises(ValueError): f26.catalog(query, stage)
            sql.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True):
            for value in ([], {str(index): [] for index in range(15)}):
                with patch.object(f26.f25, 'database_snapshot', return_value=value):
                    with self.assertRaises(SystemExit): f26.database_snapshot()

    def test_given_complete_f25_and_fixed_twenty_sources__when_preparing__then_last_old_strict_precedes_only_atomic_column_grants_and_new_strict(self):
        with tempfile.TemporaryDirectory() as directory, preparation(directory) as (_, evidence, events):
            f26.prepare()
            grant = next(event for event in events if event.startswith('BEGIN;'))
            self.assertEqual(['old-full', 'snapshot', grant, 'new-full', 'snapshot'], events)
            self.assertEqual('BEGIN;\nGRANT SELECT(note_id,expires_at),UPDATE(note_id) ON ' + f26.NOTES + ' TO pawbridge_f3_writer;\n'
                             'GRANT SELECT(member_id,note_id,direction,read_at),UPDATE(read_at) ON ' + f26.MAILBOXES + ' TO pawbridge_f3_writer;\nCOMMIT;\n', grant)
            self.assertEqual(f26.fixture_proof(OWNER), (evidence / 'f26-fixture-write.txt').read_text())
            self.assertNotIn('CREATE ', grant); self.assertNotIn('pawbridge_f2_reader', grant); self.assertNotIn('INSERT', grant)

    def test_given_old_stage_existing_receipt_source_snapshot_or_grant_failure__when_preparing__then_no_new_receipt_or_success_claim(self):
        for phase in ('old', 'existing', 'snapshot', 'grant'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory) as (_, evidence, events):
                receipt = evidence / 'f26-fixture-write.txt'
                if phase == 'existing': receipt.write_text(f26.fixture_proof(OWNER))
                target = f26.f25 if phase == 'old' else f26
                attribute = {'old': 'require_fixture', 'snapshot': 'database_snapshot', 'grant': 'owned_sql'}.get(phase)
                output = io.StringIO()
                with ExitStack() as stack:
                    if attribute: stack.enter_context(patch.object(target, attribute, side_effect=SystemExit('controlled failure')))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f26.prepare()
                self.assertEqual(phase == 'existing', receipt.exists()); self.assertNotIn('Extended only', output.getvalue())
                if phase in ('old', 'existing', 'snapshot'):
                    self.assertFalse(any(event.startswith('BEGIN;') for event in events))

    def test_given_post_grant_catalog_or_business_snapshot_failure__when_finishing_prepare__then_preserve_owned_receipt_but_never_print_success(self):
        for phase in ('strict', 'rows'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory) as (_, evidence, _):
                output = io.StringIO()
                with ExitStack() as stack:
                    if phase == 'strict': stack.enter_context(patch.object(f26, 'require_fixture', side_effect=SystemExit('catalog')))
                    else: stack.enter_context(patch.object(f26, 'database_snapshot', side_effect=[{}, {'changed': []}]))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f26.prepare()
                self.assertTrue((evidence / 'f26-fixture-write.txt').is_file()); self.assertNotIn('Extended only', output.getvalue())

    def test_given_denial_body_failure__when_unwinding__then_restore_both_planned_roles_only_after_identity_receipt_and_before_full_check(self):
        for kind in ('read', 'nickname'):
            events = []
            with self.subTest(kind=kind), patch.object(f26, 'require_fixture', side_effect=lambda: events.append('strict') or OWNER), \
                    patch.object(f26, 'require_prior_receipts', side_effect=lambda owner: events.append('identity')), \
                    patch.object(f26.f21, 'check_receipt', side_effect=lambda *args: events.append('receipt')), \
                    patch.object(f26, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(AssertionError, 'body'):
                    with f26.denied_privilege(kind): raise AssertionError('body')
            self.assertEqual('strict', events[0]); self.assertTrue(events[1].startswith('REVOKE '))
            self.assertEqual(['identity', 'receipt'], events[2:4])
            self.assertEqual(events[1].replace('REVOKE ', 'GRANT ').replace(' FROM ', ' TO '), events[4]); self.assertEqual('strict', events[5])

    def test_given_foreign_owner_or_receipt_during_denial__when_restoring__then_never_grant_to_a_different_target(self):
        for phase in ('identity', 'receipt'):
            with self.subTest(phase=phase), patch.object(f26, 'require_fixture', return_value=OWNER), \
                    patch.object(f26, 'require_prior_receipts', side_effect=SystemExit('foreign') if phase == 'identity' else None), \
                    patch.object(f26.f21, 'check_receipt', side_effect=SystemExit('foreign') if phase == 'receipt' else None), \
                    patch.object(f26, 'owned_sql') as sql:
                with self.assertRaises(SystemExit):
                    with f26.denied_privilege('read'): pass
                self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_unknown_denial_failed_revoke_or_failed_grant__when_unwinding__then_never_run_body_or_claim_restored_acl(self):
        with patch.object(f26, 'require_fixture') as strict, patch.object(f26, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f26.denied_privilege('unplanned'): self.fail('unexpected body')
            strict.assert_not_called(); sql.assert_not_called()
        for phase in ('revoke', 'grant'):
            with self.subTest(phase=phase), patch.object(f26, 'require_fixture', return_value=OWNER) as strict, \
                    patch.object(f26, 'require_prior_receipts'), patch.object(f26.f21, 'check_receipt'), \
                    patch.object(f26, 'owned_sql', side_effect=[SystemExit('revoke')] if phase == 'revoke' else ['', SystemExit('grant')]):
                with self.assertRaises(SystemExit):
                    with f26.denied_privilege('read'):
                        if phase == 'revoke': self.fail('body after failed revoke')
                strict.assert_called_once()

    def test_given_unmeasured_or_non_jar_gate__when_requiring_measurements__then_refuse_before_journey(self):
        with patch.dict(os.environ, HOSTED, clear=True):
            f26.require_measurements('jar')
            with self.assertRaises(ValueError): f26.require_measurements('results')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26, 'COUNTERS', ()), \
                patch.object(f26, 'EXPECTED_JAR_COUNTS', None):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f26.require_measurements('jar')
        bad = dict(f26.EXPECTED_JAR_COUNTS); bad[next(iter(bad))] = -1
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f26, 'EXPECTED_JAR_COUNTS', bad):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f26.require_measurements('jar')


if __name__ == '__main__':
    print('F26 fixture guards: mocked resources; immutable inputs use read-only Git objects. No actual PG/JAR/SSE evidence.', flush=True)
    unittest.main()
