"""F28 fixture controls with mocked SQL and subprocess boundaries.

Pinned input loading uses read-only Git objects, not working-tree paths. These guards do not prove
PostgreSQL syntax/ACL/rollback or SSE/JAR behavior; the packaged smoke is guarded separately.
"""
from contextlib import ExitStack, contextmanager, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f28_ci as f28

READ_ONLY_GIT = subprocess.check_output
HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
OWNER = '123-1'


def pinned_sources():
    """Capture real fixed bytes before a shared subprocess mock; never call a mocked loader."""
    result = {}
    for source, expected in f28.DDL_HASHES.items():
        raw = READ_ONLY_GIT(['git', 'show', f28.SOURCE + ':' + source], cwd=f28.ROOT.parent,
                            stderr=subprocess.PIPE)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise AssertionError('F28 fixed guard input checksum differs')
        result[source] = raw
    return result


def source_git(sources, events=None):
    def read(args, **kwargs):
        if args[:2] != ['git', 'show'] or not args[2].startswith(f28.SOURCE + ':'):
            raise AssertionError('Only fixed read-only source Git objects are permitted')
        if events is not None:
            events.append('source')
        return sources[args[2].split(':', 1)[1]]
    return read


@contextmanager
def preparation(directory, sources, events=None):
    root = Path(directory).resolve()
    evidence = root / 'build/ci'
    evidence.mkdir(parents=True)
    events = events if events is not None else []
    def sql(query='', **kwargs):
        events.append(kwargs.get('input_text', query))
        return 't'
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, HOSTED, clear=True), patch.object(f28, 'ROOT', root),
                patch.object(f28, 'EVIDENCE', evidence),
                patch.object(f28.f27, 'require_fixture', side_effect=lambda: events.append('old-full') or OWNER),
                patch.object(f28.subprocess, 'check_output', side_effect=source_git(sources, events)),
                patch.object(f28.f27, 'database_snapshot', side_effect=lambda: events.append('prior16') or {name: [] for name, _, _ in f28.SNAPSHOT_TABLES[:-1]}),
                patch.object(f28, 'require_absent_requests', side_effect=lambda: events.append('absence')),
                patch.object(f28, 'database_snapshot', side_effect=lambda: events.append('new17') or {name: [] for name, _, _ in f28.SNAPSHOT_TABLES}),
                patch.object(f28, 'require_fixture', side_effect=lambda: events.append('new-full') or OWNER),
                patch.object(f28, 'owned_sql', side_effect=sql)):
            stack.enter_context(item)
        yield root, evidence, events


class F28FixtureGuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_fixture_actions__then_refuse_before_sql_git_or_prior_stage(self):
        actions = (f28.require_owner, f28.require_fixture, f28.verified_ddl_sources, f28.prepare,
                   f28.database_snapshot, f28.require_requests_fixture, f28.require_absent_requests, f28.require_measurements, lambda: f28.owned_sql('SELECT true'), lambda: f28.require_prior_receipts(OWNER))
        for action in actions:
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f28.f3, 'sql') as sql, patch.object(f28.subprocess, 'check_output') as git, \
                    patch.object(f28.f27, 'require_owner') as owner, patch.object(f28.f27, 'require_fixture') as strict:
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
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28.f3, 'sql', side_effect=failure):
                    with self.assertRaises(SystemExit) as actual:
                        f28.owned_sql('private-sql', input_text='private-body private-email@fixture.invalid')
                self.assertIs(type(actual.exception), SystemExit)
                self.assertEqual('F28 owned fixture SQL failed without exposing command, SQL or output', str(actual.exception))
                self.assertNotRegex(str(actual.exception), 'private-|CalledProcessError|TimeoutExpired')
                self.assertIsNone(actual.exception.__cause__)
                self.assertTrue(actual.exception.__suppress_context__)

    def test_given_extended_writer__when_checking_owner__then_keep_acl_safe_f27_owner_and_exact_new_receipt_without_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28.f27, 'require_owner', return_value=OWNER) as owner, \
                patch.object(f28.f27, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f28.f21, 'check_receipt') as receipt:
            self.assertEqual(OWNER, f28.require_owner())
        owner.assert_called_once()
        receipt.assert_called_once_with(f28.EVIDENCE / 'f28-fixture-write.txt', f28.fixture_proof(OWNER))

    def test_given_revoked_user_or_writer_acl__when_checking_prior_receipts__then_use_marker_identity_without_owner_or_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28.f27, 'require_prior_receipts') as prior, \
                patch.object(f28.f27, 'require_owner', side_effect=AssertionError('User strict')), \
                patch.object(f28.f27, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f28.f21, 'check_receipt') as receipt:
            f28.require_prior_receipts(OWNER)
        prior.assert_called_once_with(OWNER)
        receipt.assert_called_once_with(f28.EVIDENCE / 'f27-fixture-write.txt', f28.f27.fixture_proof(OWNER))

    def test_given_missing_foreign_or_redirected_new_receipt__when_checking_owner__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve(); target = evidence / 'f28-fixture-write.txt'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28, 'EVIDENCE', evidence), \
                    patch.object(f28.f27, 'require_owner', return_value=OWNER):
                with self.assertRaises(SystemExit): f28.require_owner()
                target.write_text(f28.fixture_proof('foreign-owner'))
                with self.assertRaises(SystemExit): f28.require_owner()
                target.unlink(); elsewhere = evidence / 'elsewhere'; elsewhere.write_text(f28.fixture_proof(OWNER)); target.symlink_to(elsewhere)
                with self.assertRaises(SystemExit): f28.require_owner()

    def test_given_sparse_checkout_without_legacy_directories__when_verifying_ddl_inputs__then_read_only_the_two_pinned_migration_objects(self):
        sources = pinned_sources()  # Real bytes loaded before shared subprocess patch.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; root.mkdir()
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28, 'ROOT', root), \
                    patch.object(f28.subprocess, 'check_output', side_effect=source_git(sources)) as git:
                self.assertEqual(sources, f28.verified_ddl_sources())
            self.assertEqual(2, git.call_count)
            self.assertFalse((root.parent / 'community-service').exists()); self.assertFalse((root.parent / 'user-service').exists())
            self.assertEqual(set(f28.DDL_HASHES), set(sources))

    def test_given_tampered_migration_bytes__when_verifying_ddl_sources__then_reject_without_writes(self):
        sources = pinned_sources()
        for source in f28.DDL_HASHES:
            bad = dict(sources); bad[source] += b'\nchanged'
            with self.subTest(source=source), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f28.subprocess, 'check_output', side_effect=source_git(bad)), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum'): f28.verified_ddl_sources()
                write.assert_not_called()

    def test_given_unavailable_migration_object__when_verifying_ddl_sources__then_fail_closed_without_private_git_errors(self):
        failure = subprocess.CalledProcessError(1, ['private-git-command'], output='private-value', stderr='private-error')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28.subprocess, 'check_output', side_effect=failure):
            with self.assertRaises(SystemExit) as actual: f28.verified_ddl_sources()
        self.assertNotRegex(str(actual.exception), 'private-git|private-value|private-error')

    def test_given_five_inherited_tables__when_checking_catalog__then_enforce_exact_old_acl_and_only_delete_two_and_zero_acl_requests(self):
        queries = []
        with patch.object(f28, 'require_owner', return_value=OWNER), \
                patch.object(f28, 'catalog', side_effect=lambda query, stage: queries.append((query, stage)) or 't'), \
                patch.object(f28.f27, 'require_fixture', side_effect=AssertionError('old strict')):
            self.assertEqual(OWNER, f28.require_fixture())
        self.assertEqual(['shape', 'acl', 'constraints'] * 6, [stage for _, stage in queries])
        notes, mailboxes = queries[10][0], queries[13][0]
        self.assertIn("column_name,'SELECT')=(column_name IN ('note_id','expires_at'))", notes)
        self.assertIn("column_name,'UPDATE')=(column_name='note_id')", notes)
        self.assertIn("column_name,'SELECT')=(column_name IN ('member_id','note_id','direction','read_at'))", mailboxes)
        self.assertIn("column_name,'UPDATE')=(column_name IN ('read_at','favorite'))", mailboxes)
        for query in (notes, mailboxes):
            self.assertIn("column_name,'INSERT,REFERENCES')", query)
            self.assertIn("'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", query)
            self.assertIn('pg_get_serial_sequence', query)
        self.assertIn("column_name,'SELECT')=(false)", queries[4][0])  # reader members stays zero.
        self.assertIn("column_name,'UPDATE')=(column_name='member_id')", queries[4][0])
        self.assertIn("column_name,'INSERT')=(column_name IN ('member_id','blocked_id','created_at'))", queries[7][0])
        for token in ('convalidated', 'NOT condeferrable', 'confdeltype', 'member_id<>blocked_id', 'idx_private_note_blocked_member'):
            self.assertIn(token, queries[8][0])

        self.assertIn("AND has_table_privilege('pawbridge_f3_writer','" + f28.NOTES + "','DELETE')", notes)
        self.assertIn("AND has_table_privilege('pawbridge_f3_writer','" + f28.MAILBOXES + "','DELETE')", mailboxes)
        requests_acl, requests_constraints = queries[16][0], queries[17][0]
        for role in ('pawbridge_f2_reader', 'pawbridge_f3_writer'):
            self.assertIn("NOT has_column_privilege('" + role + "','" + f28.REQUESTS, requests_acl)
            self.assertIn("NOT has_table_privilege('" + role + "','" + f28.REQUESTS, requests_acl)
        for token in ('count(*)=3 FROM pg_constraint', "confdeltype='c'", "confdeltype='n'", 'count(*)=4 FROM pg_index',
                      'idx_private_note_request_rate', 'idx_private_note_request_note', 'idx_private_note_request_cleanup',
                      'i.indoption[1]=3', 'note_idISNOTNULL', 'note_idISNULL'):
            self.assertIn(token, requests_constraints)

    def test_given_rejected_shape_extra_acl_constraint_or_sequence_catalog_result__when_checking_fixture__then_stop_at_that_boundary(self):
        # Catalog booleans are controlled inputs, not an emulation of PostgreSQL privilege evaluation.
        for index in range(18):
            with self.subTest(index=index), patch.object(f28, 'require_owner', return_value=OWNER), \
                    patch.object(f28, 'catalog', side_effect=['t'] * index + ['f']) as catalog:
                with self.assertRaises(SystemExit): f28.require_fixture()
                self.assertEqual(index + 1, catalog.call_count)

    def test_given_non_catalog_statement_or_incomplete_snapshot__when_checking__then_refuse_without_sql_or_partial_snapshot(self):
        with patch.object(f28, 'owned_sql') as sql:
            for query, stage in (('DELETE FROM private_notes', 'acl'), ('SELECT true', 'business')):
                with self.assertRaises(ValueError): f28.catalog(query, stage)
            sql.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28.f27, 'database_snapshot', side_effect=AssertionError('old16')):
            for value in ([], {str(index): [] for index in range(16)}, {name: None for name, _, _ in f28.SNAPSHOT_TABLES}):
                with patch.object(f28, 'owned_sql', return_value=json.dumps(value)):
                    with self.assertRaises(SystemExit): f28.database_snapshot()
            complete = {name: [] for name, _, _ in f28.SNAPSHOT_TABLES}
            with patch.object(f28, 'owned_sql', return_value=json.dumps(complete)) as sql:
                self.assertEqual(complete, f28.database_snapshot())
            self.assertEqual(1, sql.call_count); self.assertIn('private_note_requests', sql.call_args.args[0])

    def test_given_pinned_request_migrations__when_building_ddl__then_only_four_lf_fragments_and_delete_two_are_qualified(self):
        sources = pinned_sources()
        ddl = f28.verified_requests_ddl(sources)
        self.assertTrue(ddl.startswith('BEGIN;\nCREATE TABLE pawbridge_community.private_note_requests ('))
        self.assertTrue(ddl.endswith('GRANT DELETE ON ' + f28.MAILBOXES + ',' + f28.NOTES + ' TO pawbridge_f3_writer;\nCOMMIT;\n'))
        self.assertEqual(1, ddl.count('CREATE TABLE ')); self.assertEqual(3, ddl.count('CREATE INDEX '))
        self.assertIn('REFERENCES pawbridge_community.private_notes(note_id) ON DELETE SET NULL', ddl)
        self.assertIn('REFERENCES pawbridge_community.private_note_members(member_id) ON DELETE CASCADE', ddl)
        self.assertNotIn('CREATE TABLE pawbridge_community.private_notes ', ddl)
        self.assertNotIn('GRANT SELECT', ddl); self.assertNotIn('GRANT UPDATE', ddl)
        bad = dict(sources); bad[f28.MIGRATIONS + 'V6__private_notes.sql'] += b'\nchanged'
        with self.assertRaises(SystemExit): f28.verified_requests_ddl(bad)
        with patch.object(f28, 'FRAGMENT_HASHES', ('0' * 64,) + f28.FRAGMENT_HASHES[1:]):
            with self.assertRaisesRegex(SystemExit, 'fragment identity'): f28.verified_requests_ddl(sources)

    def test_given_a_request_table_index_or_type_collision__when_requiring_absence__then_stop_before_any_write(self):
        for index in (0, 1):
            with self.subTest(index=index), patch.object(f28, 'catalog', side_effect=['t'] * index + ['f']) as catalog, patch.object(f28, 'owned_sql') as sql:
                with self.assertRaises(SystemExit): f28.require_absent_requests()
                self.assertEqual(index + 1, catalog.call_count); sql.assert_not_called()

    def test_given_complete_f27_and_fixed_twenty_sources__when_preparing__then_last_old_strict_precedes_only_atomic_request_ddl_and_delete_grants_and_new_strict(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
            f28.prepare()
            grant = next(event for event in events if event.startswith('BEGIN;'))
            self.assertEqual(['old-full'] + ['source'] * 2 + ['prior16', 'absence', grant, 'new-full', 'new17'], events)
            self.assertEqual(f28.verified_requests_ddl(sources), grant)
            self.assertEqual(f28.fixture_proof(OWNER), (evidence / 'f28-fixture-write.txt').read_text())
            self.assertEqual(1, grant.count('CREATE TABLE ')); self.assertEqual(3, grant.count('CREATE INDEX ')); self.assertEqual(1, grant.count('GRANT ')); self.assertNotIn('pawbridge_f2_reader', grant); self.assertNotIn('GRANT SELECT', grant); self.assertNotIn('GRANT UPDATE', grant)

    def test_given_old_stage_existing_receipt_source_snapshot_or_grant_failure__when_preparing__then_no_new_receipt_or_success_claim(self):
        sources = pinned_sources()
        for phase in ('old', 'existing', 'source', 'snapshot', 'absence', 'grant'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
                receipt = evidence / 'f28-fixture-write.txt'
                if phase == 'existing': receipt.write_text(f28.fixture_proof(OWNER))
                target = f28.f27 if phase in ('old', 'snapshot') else f28
                attribute = {'old': 'require_fixture', 'source': 'verified_ddl_sources', 'snapshot': 'database_snapshot', 'absence': 'require_absent_requests', 'grant': 'owned_sql'}.get(phase)
                output = io.StringIO()
                with ExitStack() as stack:
                    if attribute: stack.enter_context(patch.object(target, attribute, side_effect=SystemExit('controlled failure')))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f28.prepare()
                self.assertEqual(phase == 'existing', receipt.exists()); self.assertNotIn('Added only', output.getvalue())
                if phase in ('old', 'existing', 'source', 'snapshot', 'absence'):
                    self.assertFalse(any(event.startswith('BEGIN;') for event in events))

    def test_given_post_grant_catalog_or_business_snapshot_failure__when_finishing_prepare__then_preserve_owned_receipt_but_never_print_success(self):
        sources = pinned_sources()
        for phase in ('strict', 'rows'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, _):
                output = io.StringIO()
                with ExitStack() as stack:
                    if phase == 'strict': stack.enter_context(patch.object(f28, 'require_fixture', side_effect=SystemExit('catalog')))
                    else: stack.enter_context(patch.object(f28, 'database_snapshot', return_value={**{name: [] for name, _, _ in f28.SNAPSHOT_TABLES}, 'private_note_requests': [{'synthetic': 'unexpected'}]}))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f28.prepare()
                self.assertTrue((evidence / 'f28-fixture-write.txt').is_file()); self.assertNotIn('Added only', output.getvalue())

    def test_given_denial_body_failure__when_unwinding__then_restore_both_planned_roles_only_after_identity_receipt_and_before_full_check(self):
        for kind in ('mailbox-delete', 'note-delete', 'nickname'):
            events = []
            with self.subTest(kind=kind), patch.object(f28, 'require_fixture', side_effect=lambda: events.append('strict') or OWNER), \
                    patch.object(f28, 'require_prior_receipts', side_effect=lambda owner: events.append('identity')), \
                    patch.object(f28.f21, 'check_receipt', side_effect=lambda *args: events.append('receipt')), \
                    patch.object(f28, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(AssertionError, 'body'):
                    with f28.denied_privilege(kind): raise AssertionError('body')
            self.assertEqual('strict', events[0]); self.assertTrue(events[1].startswith('REVOKE '))
            self.assertEqual(['identity', 'receipt'], events[2:4])
            self.assertEqual(events[1].replace('REVOKE ', 'GRANT ').replace(' FROM ', ' TO '), events[4]); self.assertEqual('strict', events[5])

    def test_given_foreign_owner_or_receipt_during_denial__when_restoring__then_never_grant_to_a_different_target(self):
        for phase in ('identity', 'receipt'):
            with self.subTest(phase=phase), patch.object(f28, 'require_fixture', return_value=OWNER), \
                    patch.object(f28, 'require_prior_receipts', side_effect=SystemExit('foreign') if phase == 'identity' else None), \
                    patch.object(f28.f21, 'check_receipt', side_effect=SystemExit('foreign') if phase == 'receipt' else None), \
                    patch.object(f28, 'owned_sql') as sql:
                with self.assertRaises(SystemExit):
                    with f28.denied_privilege('mailbox-delete'): pass
                self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_unknown_denial_failed_revoke_or_failed_grant__when_unwinding__then_never_run_body_or_claim_restored_acl(self):
        with patch.object(f28, 'require_fixture') as strict, patch.object(f28, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f28.denied_privilege('unplanned'): self.fail('unexpected body')
            strict.assert_not_called(); sql.assert_not_called()
        for phase in ('revoke', 'grant'):
            with self.subTest(phase=phase), patch.object(f28, 'require_fixture', return_value=OWNER) as strict, \
                    patch.object(f28, 'require_prior_receipts'), patch.object(f28.f21, 'check_receipt'), \
                    patch.object(f28, 'owned_sql', side_effect=[SystemExit('revoke')] if phase == 'revoke' else ['', SystemExit('grant')]):
                with self.assertRaises(SystemExit):
                    with f28.denied_privilege('mailbox-delete'):
                        if phase == 'revoke': self.fail('body after failed revoke')
                strict.assert_called_once()

    def test_given_unmeasured_or_non_jar_gate__when_requiring_measurements__then_refuse_before_journey(self):
        with patch.dict(os.environ, HOSTED, clear=True):
            f28.require_measurements('jar')
            with self.assertRaises(ValueError): f28.require_measurements('results')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28, 'COUNTERS', ()), \
                patch.object(f28, 'EXPECTED_JAR_COUNTS', None):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f28.require_measurements('jar')
        bad = dict(f28.EXPECTED_JAR_COUNTS); bad[next(iter(bad))] = -1
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f28, 'EXPECTED_JAR_COUNTS', bad):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f28.require_measurements('jar')


if __name__ == '__main__':
    print('F28 fixture guards: mocked resources; immutable inputs use read-only Git objects. No actual PG/JAR/SSE evidence.', flush=True)
    unittest.main()
