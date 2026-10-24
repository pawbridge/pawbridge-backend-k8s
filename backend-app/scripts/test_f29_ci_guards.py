"""F29 fixture controls and late member, administrator and auth-session fixture guards.

The unit run mocks SQL and subprocess boundaries. Pinned migration input loading uses
read-only Git objects, not working-tree paths. These guards do not prove PostgreSQL
syntax/ACL/rollback or SSE/JAR behavior.
Runtime smoke/token/seed/cleanup guards are separately owned and not represented here.
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

import f29_ci as f29

READ_ONLY_GIT = subprocess.check_output
HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
OWNER = '123-1'
def pinned_sources(expected_hashes=None):
    """Capture real fixed bytes before a shared subprocess mock; never call a mocked loader."""
    result = {}
    for source, expected in (expected_hashes or f29.DDL_HASHES).items():
        raw = READ_ONLY_GIT(['git', 'show', f29.SOURCE + ':' + source], cwd=f29.ROOT.parent,
                            stderr=subprocess.PIPE)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise AssertionError('F29 fixed guard input checksum differs')
        result[source] = raw
    return result


def source_git(sources, events=None):
    def read(args, **kwargs):
        if args[:2] != ['git', 'show'] or not args[2].startswith(f29.SOURCE + ':'):
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
        for item in (patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root),
                patch.object(f29, 'EVIDENCE', evidence),
                patch.object(f29.f28, 'require_fixture', side_effect=lambda: events.append('old-full') or OWNER),
                patch.object(f29.subprocess, 'check_output', side_effect=source_git(sources, events)),
                patch.object(f29.f28, 'database_snapshot', side_effect=lambda: events.append('prior17') or {name: [] for name, _, _ in f29.SNAPSHOT_TABLES[:-1]}),
                patch.object(f29, 'require_absent_reports', side_effect=lambda: events.append('absence')),
                patch.object(f29, 'database_snapshot', side_effect=lambda: events.append('new18') or {name: [] for name, _, _ in f29.SNAPSHOT_TABLES}),
                patch.object(f29, 'require_fixture', side_effect=lambda: events.append('new-full') or OWNER),
                patch.object(f29, 'owned_sql', side_effect=sql)):
            stack.enter_context(item)
        yield root, evidence, events


class F29FixtureGuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_fixture_actions__then_refuse_before_sql_git_or_prior_stage(self):
        actions = (f29.require_owner, f29.require_fixture, f29.verified_sources, f29.prepare,
                   f29.database_snapshot, f29.require_requests_fixture, f29.require_reports_fixture,
                   f29.require_absent_reports, f29.require_measurements,
                   lambda: f29.owned_sql('SELECT true'), lambda: f29.require_prior_receipts(OWNER))
        for action in actions:
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f29.f3, 'sql') as sql, patch.object(f29.subprocess, 'check_output') as git, \
                    patch.object(f29.f28, 'require_owner') as owner, patch.object(f29.f28, 'require_fixture') as strict:
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
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.f3, 'sql', side_effect=failure):
                    with self.assertRaises(SystemExit) as actual:
                        f29.owned_sql('private-sql', input_text='private-body private-email@fixture.invalid')
                self.assertIs(type(actual.exception), SystemExit)
                self.assertEqual('F29 owned fixture SQL failed without exposing command, SQL or output', str(actual.exception))
                self.assertNotRegex(str(actual.exception), 'private-|CalledProcessError|TimeoutExpired')
                self.assertIsNone(actual.exception.__cause__)
                self.assertTrue(actual.exception.__suppress_context__)

    def test_given_extended_writer__when_checking_owner__then_keep_acl_safe_f28_owner_and_exact_new_receipt_without_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.f28, 'require_owner', return_value=OWNER) as owner, \
                patch.object(f29.f28, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f29.f21, 'check_receipt') as receipt:
            self.assertEqual(OWNER, f29.require_owner())
        owner.assert_called_once()
        receipt.assert_called_once_with(f29.EVIDENCE / 'f29-fixture-write.txt', f29.fixture_proof(OWNER))

    def test_given_revoked_user_or_writer_acl__when_checking_prior_receipts__then_use_marker_identity_without_owner_or_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.f28, 'require_prior_receipts') as prior, \
                patch.object(f29.f28, 'require_owner', side_effect=AssertionError('User strict')), \
                patch.object(f29.f28, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f29.f21, 'check_receipt') as receipt:
            f29.require_prior_receipts(OWNER)
        prior.assert_called_once_with(OWNER)
        receipt.assert_called_once_with(f29.EVIDENCE / 'f28-fixture-write.txt', f29.f28.fixture_proof(OWNER))

    def test_given_missing_foreign_or_redirected_new_receipt__when_checking_owner__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve(); target = evidence / 'f29-fixture-write.txt'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'EVIDENCE', evidence), \
                    patch.object(f29.f28, 'require_owner', return_value=OWNER):
                with self.assertRaises(SystemExit): f29.require_owner()
                target.write_text(f29.fixture_proof('foreign-owner'))
                with self.assertRaises(SystemExit): f29.require_owner()
                target.unlink(); elsewhere = evidence / 'elsewhere'; elsewhere.write_text(f29.fixture_proof(OWNER)); target.symlink_to(elsewhere)
                with self.assertRaises(SystemExit): f29.require_owner()

    def test_given_sparse_checkout_without_service_directories__when_verifying_source_inputs__then_read_only_the_two_pinned_report_migrations(self):
        sources = pinned_sources()  # Real bytes loaded before shared subprocess patch.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'
            root.mkdir()
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(sources)) as git:
                self.assertEqual(sources, f29.verified_sources())
            self.assertEqual(2, git.call_count)
            self.assertEqual(set(f29.DDL_HASHES), set(sources))
            self.assertFalse((root.parent / 'community-service').exists())

    def test_given_tampered_report_migration_bytes__when_verifying_sources__then_reject_without_writes(self):
        sources = pinned_sources()
        for source in f29.DDL_HASHES:
            bad = dict(sources)
            bad[source] += b'\nchanged'
            with self.subTest(source=source), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(bad)), \
                    patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum'):
                    f29.verified_sources()
                write.assert_not_called()

    def test_given_unavailable_object__when_verifying_sources__then_fail_closed_without_private_git_errors(self):
        failure = subprocess.CalledProcessError(1, ['private-git-command'], output='private-value', stderr='private-error')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.subprocess, 'check_output', side_effect=failure):
            with self.assertRaises(SystemExit) as actual:
                f29.verified_sources()
        self.assertNotRegex(str(actual.exception), 'private-git|private-value|private-error')

    def test_given_six_inherited_tables_and_reports__when_checking_catalog__then_keep_reader_and_only_the_exact_send_column_grants(self):
        queries = []
        with patch.object(f29, 'require_owner', return_value=OWNER), \
                patch.object(f29, 'catalog', side_effect=lambda query, stage: queries.append((query, stage)) or 't'), \
                patch.object(f29.f28, 'require_fixture', side_effect=AssertionError('old strict')):
            self.assertEqual(OWNER, f29.require_fixture())
        self.assertEqual(['shape', 'acl', 'constraints'] * 7 + ['sequence'], [stage for _, stage in queries])
        notes, boxes, requests = queries[10][0], queries[13][0], queries[16][0]
        self.assertIn("column_name,'SELECT')=(column_name IN ('note_id','expires_at','sender_id'))", notes)
        self.assertIn("column_name,'INSERT')=(true)", notes)
        self.assertIn("column_name,'UPDATE')=(column_name='note_id')", notes)
        self.assertIn("column_name,'SELECT')=(column_name IN ('member_id','note_id','direction','read_at'))", boxes)
        self.assertIn("column_name,'INSERT')=(column_name IN ('member_id','note_id','direction','read_at','created_at'))", boxes)
        self.assertIn("column_name,'UPDATE')=(column_name IN ('read_at','favorite'))", boxes)
        for query in (notes, boxes):
            self.assertIn("AND has_table_privilege('pawbridge_f3_writer'", query)
            self.assertIn("column_name,'REFERENCES')", query)
            self.assertIn('pg_get_serial_sequence', query)
        self.assertIn("column_name,'SELECT')=(false)", queries[4][0])
        self.assertIn("column_name,'UPDATE')=(column_name='member_id')", queries[4][0])
        self.assertIn("column_name,'INSERT')=(column_name IN ('member_id','blocked_id','created_at'))", queries[7][0])
        for token in ('convalidated', 'NOT condeferrable', 'confdeltype', 'member_id<>blocked_id', 'idx_private_note_blocked_member'):
            self.assertIn(token, queries[8][0])
        self.assertIn("has_column_privilege('pawbridge_f3_writer','" + f29.REQUESTS + "',column_name,'SELECT') AND has_column_privilege", requests)
        self.assertIn("column_name,'INSERT') AND NOT has_column_privilege", requests)
        self.assertIn("column_name,'UPDATE,REFERENCES')", requests)
        for role in ('pawbridge_f2_reader','pawbridge_f3_writer'):
            self.assertIn("NOT has_table_privilege('" + role + "','" + f29.REQUESTS, requests)
        self.assertIn("NOT has_column_privilege('pawbridge_f2_reader'", requests)
        for token in ('count(*)=3 FROM pg_constraint', "confdeltype='c'", "confdeltype='n'", 'count(*)=4 FROM pg_index',
                      'idx_private_note_request_rate', 'idx_private_note_request_note', 'idx_private_note_request_cleanup'):
            self.assertIn(token, queries[17][0])
        shape, acl, constraints, sequence = [row[0] for row in queries[18:]]
        for token in ('count(*)=21', 'identity_generation', 'BY DEFAULT', 'is_identity', 'image_urls', 'district', 'animal_type'):
            self.assertIn(token, shape)
        self.assertIn("column_name IN ('report_id','author_id','deleted_at')", acl)
        self.assertIn("NOT has_column_privilege('pawbridge_f2_reader'", acl)
        self.assertIn("column_name,'INSERT,UPDATE,REFERENCES')", acl)
        for token in ('count(*)=4 FROM pg_constraint', 'report_kind=ANYARRAY', 'animal_typeISNULLORanimal_type',
                      'districtISNULLORprovinceISNOTNULL', 'count(*)=4 FROM pg_index', 'deleted_atISNULL',
                      'i.indoption[1]=3', 'i.indoption[2]=3', 'NOT tgisinternal'):
            self.assertIn(token, constraints)
        for token in ("c.relkind='S'", "CASE WHEN c.relkind='S' THEN", "d.deptype='i'", 's.seqmax=9223372036854775807',
                      'animal_reports_report_id_seq', "NOT has_sequence_privilege('pawbridge_f2_reader'", "NOT has_sequence_privilege('pawbridge_f3_writer'"):
            self.assertIn(token, sequence)

    def test_given_rejected_shape_extra_acl_constraint_or_sequence_catalog_result__when_checking_fixture__then_stop_at_that_boundary(self):
        # Catalog booleans are controlled inputs, not actual PostgreSQL privilege evaluation or query syntax evidence.
        for index in range(22):
            with self.subTest(index=index), patch.object(f29, 'require_owner', return_value=OWNER), \
                    patch.object(f29, 'catalog', side_effect=['t'] * index + ['f']) as catalog:
                with self.assertRaises(SystemExit): f29.require_fixture()
                self.assertEqual(index + 1, catalog.call_count)

    def test_given_non_catalog_statement_or_incomplete_snapshot__when_checking__then_refuse_without_sql_or_partial_snapshot(self):
        with patch.object(f29, 'owned_sql') as sql:
            for query, stage in (('DELETE FROM private_notes', 'acl'), ('SELECT true', 'business')):
                with self.assertRaises(ValueError): f29.catalog(query, stage)
            sql.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.f28, 'database_snapshot', side_effect=AssertionError('old17')):
            for value in ([], {str(index): [] for index in range(17)}, {name: None for name, _, _ in f29.SNAPSHOT_TABLES}):
                with patch.object(f29, 'owned_sql', return_value=json.dumps(value)):
                    with self.assertRaises(SystemExit): f29.database_snapshot()
            complete = {name: [] for name, _, _ in f29.SNAPSHOT_TABLES}
            with patch.object(f29, 'owned_sql', return_value=json.dumps(complete)) as sql:
                self.assertEqual(complete, f29.database_snapshot())
            self.assertEqual(1, sql.call_count); self.assertIn('animal_reports', sql.call_args.args[0])

    def test_given_pinned_report_migrations__when_building_ddl__then_only_five_original_fragments_and_exact_four_send_grants_are_qualified(self):
        sources = pinned_sources()
        ddl = f29.verified_reports_ddl(sources)
        self.assertTrue(ddl.startswith('BEGIN;\nCREATE TABLE pawbridge_community.animal_reports ('))
        self.assertTrue(ddl.endswith(f29.SEND_GRANTS + 'COMMIT;\n'))
        self.assertEqual(1, ddl.count('CREATE TABLE ')); self.assertEqual(1, ddl.count('ALTER TABLE ')); self.assertEqual(3, ddl.count('CREATE INDEX '))
        self.assertEqual(4, ddl.count('GRANT ')); self.assertIn('GENERATED BY DEFAULT AS IDENTITY', ddl)
        self.assertIn('ON pawbridge_community.animal_reports', ddl); self.assertIn('ALTER TABLE pawbridge_community.animal_reports', ddl)
        self.assertIn('INSERT(member_id,note_id,direction,read_at,created_at)', ddl)
        self.assertIn('SELECT(sender_id,request_id,request_hash,note_id,created_at),INSERT(sender_id,request_id,request_hash,note_id,created_at)', ddl)
        self.assertIn('SELECT(report_id,author_id,deleted_at)', ddl)
        for forbidden in ('pawbridge_f2_reader', 'GRANT UPDATE', 'GRANT DELETE', 'GRANT USAGE', 'GRANT ALL', 'CREATE SCHEMA', 'CREATE SEQUENCE', 'INSERT(member_id,note_id,direction,read_at,favorite'):
            self.assertNotIn(forbidden, ddl)
        for path in f29.DDL_HASHES:
            bad = dict(sources); bad[path] += b'\nchanged'
            with self.assertRaises(SystemExit): f29.verified_reports_ddl(bad)
        with patch.object(f29, 'FRAGMENT_HASHES', ('0' * 64,) + f29.FRAGMENT_HASHES[1:]):
            with self.assertRaisesRegex(SystemExit, 'statement LF identity'): f29.verified_reports_ddl(sources)

    def test_given_a_report_table_identity_index_check_or_type_collision__when_requiring_absence__then_stop_before_any_write(self):
        for index in (0, 1, 2):
            with self.subTest(index=index), patch.object(f29, 'catalog', side_effect=['t'] * index + ['f']) as catalog, patch.object(f29, 'owned_sql') as sql:
                with self.assertRaises(SystemExit): f29.require_absent_reports()
                self.assertEqual(index + 1, catalog.call_count); sql.assert_not_called()

    def test_given_complete_f28_and_two_pinned_report_migrations__when_preparing__then_last_old_strict_precedes_only_atomic_report_ddl_and_send_grants_and_new_strict(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
            f29.prepare()
            grant = next(event for event in events if event.startswith('BEGIN;'))
            self.assertEqual(['old-full'] + ['source'] * 2 + ['prior17', 'absence', grant, 'new-full', 'new18'], events)
            self.assertEqual(f29.verified_reports_ddl(sources), grant)
            self.assertEqual(f29.fixture_proof(OWNER), (evidence / 'f29-fixture-write.txt').read_text())
            self.assertEqual(1, grant.count('CREATE TABLE ')); self.assertEqual(3, grant.count('CREATE INDEX ')); self.assertEqual(4, grant.count('GRANT ')); self.assertNotIn('pawbridge_f2_reader', grant); self.assertNotIn('GRANT DELETE', grant); self.assertNotIn('GRANT UPDATE', grant)

    def test_given_old_stage_existing_receipt_source_snapshot_or_grant_failure__when_preparing__then_no_new_receipt_or_success_claim(self):
        sources = pinned_sources()
        for phase in ('old', 'existing', 'source', 'snapshot', 'absence', 'grant'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
                receipt = evidence / 'f29-fixture-write.txt'
                if phase == 'existing': receipt.write_text(f29.fixture_proof(OWNER))
                target = f29.f28 if phase in ('old', 'snapshot') else f29
                attribute = {'old': 'require_fixture', 'source': 'verified_sources', 'snapshot': 'database_snapshot', 'absence': 'require_absent_reports', 'grant': 'owned_sql'}.get(phase)
                output = io.StringIO()
                with ExitStack() as stack:
                    if attribute: stack.enter_context(patch.object(target, attribute, side_effect=SystemExit('controlled failure')))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f29.prepare()
                self.assertEqual(phase == 'existing', receipt.exists()); self.assertNotIn('Added only', output.getvalue())
                if phase in ('old', 'existing', 'source', 'snapshot', 'absence'):
                    self.assertFalse(any(event.startswith('BEGIN;') for event in events))

    def test_given_post_grant_catalog_or_business_snapshot_failure__when_finishing_prepare__then_preserve_owned_receipt_but_never_print_success(self):
        sources = pinned_sources()
        for phase in ('strict', 'rows'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, _):
                output = io.StringIO()
                with ExitStack() as stack:
                    if phase == 'strict': stack.enter_context(patch.object(f29, 'require_fixture', side_effect=SystemExit('catalog')))
                    else: stack.enter_context(patch.object(f29, 'database_snapshot', return_value={**{name: [] for name, _, _ in f29.SNAPSHOT_TABLES}, 'animal_reports': [{'synthetic': 'unexpected'}]}))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f29.prepare()
                self.assertTrue((evidence / 'f29-fixture-write.txt').is_file()); self.assertNotIn('Added only', output.getvalue())

    def test_given_denial_body_failure__when_unwinding__then_restore_only_planned_columns_and_roles_only_after_identity_receipt_and_before_full_check(self):
        for kind in ('note-insert', 'mailbox-insert', 'request-insert', 'report-context', 'nickname'):
            events = []
            with self.subTest(kind=kind), patch.object(f29, 'require_fixture', side_effect=lambda: events.append('strict') or OWNER), \
                    patch.object(f29, 'require_prior_receipts', side_effect=lambda owner: events.append('identity')), \
                    patch.object(f29.f21, 'check_receipt', side_effect=lambda *args: events.append('receipt')), \
                    patch.object(f29, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(AssertionError, 'body'):
                    with f29.denied_privilege(kind): raise AssertionError('body')
            self.assertEqual('strict', events[0]); self.assertTrue(events[1].startswith('REVOKE '))
            self.assertEqual(['identity', 'receipt'], events[2:4])
            self.assertEqual(events[1].replace('REVOKE ', 'GRANT ').replace(' FROM ', ' TO '), events[4]); self.assertEqual('strict', events[5])

    def test_given_foreign_owner_or_receipt_during_denial__when_restoring__then_never_grant_to_a_different_target(self):
        for phase in ('identity', 'receipt'):
            with self.subTest(phase=phase), patch.object(f29, 'require_fixture', return_value=OWNER), \
                    patch.object(f29, 'require_prior_receipts', side_effect=SystemExit('foreign') if phase == 'identity' else None), \
                    patch.object(f29.f21, 'check_receipt', side_effect=SystemExit('foreign') if phase == 'receipt' else None), \
                    patch.object(f29, 'owned_sql') as sql:
                with self.assertRaises(SystemExit):
                    with f29.denied_privilege('note-insert'): pass
                self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_unknown_denial_failed_revoke_or_failed_grant__when_unwinding__then_never_run_body_or_claim_restored_acl(self):
        with patch.object(f29, 'require_fixture') as strict, patch.object(f29, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f29.denied_privilege('unplanned'): self.fail('unexpected body')
            strict.assert_not_called(); sql.assert_not_called()
        for phase in ('revoke', 'grant'):
            with self.subTest(phase=phase), patch.object(f29, 'require_fixture', return_value=OWNER) as strict, \
                    patch.object(f29, 'require_prior_receipts'), patch.object(f29.f21, 'check_receipt'), \
                    patch.object(f29, 'owned_sql', side_effect=[SystemExit('revoke')] if phase == 'revoke' else ['', SystemExit('grant')]):
                with self.assertRaises(SystemExit):
                    with f29.denied_privilege('note-insert'):
                        if phase == 'revoke': self.fail('body after failed revoke')
                strict.assert_called_once()

class MemberCleanupLateGuardTest(unittest.TestCase):
    """Changed late fixture controls only; mock SQL never proves PG or managers."""
    @staticmethod
    def sources():
        return pinned_sources(f29.MEMBER_DDL_HASHES)

    def test_given_exact_member_ddl_fragments__when_qualifying__then_only_six_tables_and_minimal_cleanup_grants_are_assembled_without_running_sql(self):
        sources=self.sources()
        with patch.object(f29.subprocess,'check_output',side_effect=source_git(sources)),patch.object(f29,'owned_sql') as sql:
            ddl=f29.member_verified_ddl()
        sql.assert_not_called();self.assertTrue(ddl.startswith('BEGIN;'));self.assertTrue(ddl.endswith('COMMIT;\n'))
        self.assertEqual(6,ddl.count('CREATE TABLE '));self.assertIn('ON DELETE CASCADE',ddl)
        self.assertIn('ck_rollback_charset_refresh_tokens',ddl);self.assertIn('WHERE status = \'PENDING\'',ddl)
        self.assertIn('UPDATE(user_id)',ddl);self.assertIn('UPDATE(context_type,context_id)',ddl)
        self.assertNotIn('GRANT ALL',ddl);self.assertNotIn('GRANT USAGE',ddl);self.assertNotIn('CREATE SCHEMA',ddl)
        self.assertNotIn('CREATE TABLE pawbridge_user.outbox_events',ddl)

    def test_given_user_acl_is_intentionally_denied__when_checking_member_identity__then_use_hosted_marker_only_receipts_and_never_old_acl_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve()
            with patch.object(f29, 'EVIDENCE', evidence), \
                    patch.object(f29.f2, 'require_hosted', return_value=OWNER), \
                    patch.object(f29, 'require_prior_receipts') as prior, \
                    patch.object(f29.f28, 'require_owner') as old, \
                    patch.object(f29.f21, 'check_receipt') as receipt, \
                    patch.object(f29, 'owned_sql') as sql:
                self.assertEqual(OWNER, f29.member_require_identity())
            prior.assert_called_once_with(OWNER)
            old.assert_not_called()
            sql.assert_not_called()
            self.assertEqual([evidence / 'f29-fixture-write.txt', evidence / f29.MEMBER_RECEIPT],
                             [call.args[0] for call in receipt.call_args_list])

    def test_given_complete_old_fixture__when_late_member_prepare_runs__then_old_strict_once_precedes_only_atomic_extension_and_new24_checks(self):
        sources = self.sources()
        events = []
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve()
            before = {key: [] for key, _, _ in f29.SNAPSHOT_TABLES}
            after = dict(before, **{key: [] for key, _, _ in f29.MEMBER_TABLES})
            def sql(query='', **kwargs):
                events.append('write' if 'input_text' in kwargs else 'absence')
                return 't'
            with patch.object(f29.f2, 'require_hosted', return_value=OWNER), \
                    patch.object(f29, 'require_fixture', side_effect=lambda: events.append('old') or OWNER), \
                    patch.object(f29, 'EVIDENCE', evidence), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(sources, events)), \
                    patch.object(f29, 'owned_sql', side_effect=sql), \
                    patch.object(f29, 'database_snapshot', return_value=before), \
                    patch.object(f29, 'member_require_fixture', side_effect=lambda: events.append('new') or OWNER), \
                    patch.object(f29, 'member_database_snapshot', return_value=after):
                f29.member_cleanup_prepare()
            self.assertEqual(['old'] + ['source'] * 3, events[:4])
            self.assertEqual(1, events.count('old'))
            self.assertEqual(1, events.count('write'))
            self.assertLess(events.index('write'), events.index('new'))
            self.assertEqual(f29.member_fixture_proof(OWNER), (evidence / f29.MEMBER_RECEIPT).read_text())

    def test_given_changed_member_migration_bytes__when_preparing_member_cleanup__then_reject_before_any_ddl_or_grant(self):
        sources = dict(self.sources())
        first = next(iter(f29.MEMBER_DDL_HASHES))
        sources[first] += b'\nchanged'
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve()
            with patch.object(f29.f2, 'require_hosted', return_value=OWNER), \
                    patch.object(f29, 'require_fixture', return_value=OWNER) as old, \
                    patch.object(f29, 'EVIDENCE', evidence), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(sources)), \
                    patch.object(f29, 'owned_sql') as sql, \
                    patch.object(f29, 'database_snapshot') as snapshot:
                with self.assertRaisesRegex(SystemExit, 'Member cleanup original DDL source differs'):
                    f29.member_cleanup_prepare()
            sql.assert_not_called()
            snapshot.assert_not_called()
            old.assert_called_once_with()
            self.assertFalse((evidence / f29.MEMBER_RECEIPT).exists())


class AuthSessionsFixtureGuardTest(unittest.TestCase):
    def test_given_owned_old_acl__when_extending_then_denying__then_old_strict_runs_once_and_identity_restores_exact_rights(self):
        events=[]
        with tempfile.TemporaryDirectory() as directory, patch.object(f29,'EVIDENCE',Path(directory).resolve()), \
                patch.object(f29.f2,'require_hosted'), patch.object(f29,'admin_update_require_fixture',side_effect=lambda:events.append('old') or OWNER) as old, \
                patch.object(f29,'auth_sessions_require_fixture',side_effect=lambda:events.append('new') or OWNER), \
                patch.object(f29,'auth_sessions_require_identity',side_effect=lambda:events.append('identity') or OWNER), \
                patch.object(f29,'member_database_snapshot',return_value={'all24':'same'}), \
                patch.object(f29,'auth_sessions_ddl',side_effect=lambda:events.append('ddl') or 'CREATE TABLE auth_sessions ();'), \
                patch.object(f29,'owned_sql',side_effect=lambda query='',input_text=None:events.append(input_text or query) or ('t' if 'to_regclass' in query else '')):
            f29.auth_sessions_prepare()
            self.assertEqual(f29.auth_sessions_fixture_proof(OWNER),(Path(directory)/f29.AUTH_SESSIONS_RECEIPT).read_text())
            self.assertEqual(1,events.count('old')); self.assertLess(events.index('ddl'),next(i for i,e in enumerate(events) if 'GRANT ' in e))
            write=next(e for e in events if 'GRANT ' in e)
            self.assertTrue(write.startswith('BEGIN;\nSET LOCAL search_path TO pawbridge_user;\nCREATE TABLE auth_sessions ();\n'))
            self.assertTrue(write.endswith(f29.AUTH_SESSIONS_GRANTS+'\nCOMMIT;\n'))
            old.reset_mock()
            with self.assertRaisesRegex(RuntimeError,'original failure'):
                with f29.auth_sessions_denied_privilege('rotate-write'): raise RuntimeError('original failure')
            old.assert_not_called()
            self.assertEqual('GRANT UPDATE(token_hash) ON pawbridge_user.auth_sessions TO pawbridge_f3_writer',events[-2])
            self.assertEqual('new',events[-1]); self.assertIn('identity',events)



    def test_given_an_existing_auth_table__when_preparing__then_refuse_before_any_write(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f29,'EVIDENCE',Path(directory).resolve()), \
                patch.object(f29.f2,'require_hosted'), patch.object(f29,'admin_update_require_fixture',return_value=OWNER), \
                patch.object(f29,'auth_sessions_ddl',return_value='CREATE TABLE auth_sessions ();'), \
                patch.object(f29,'member_database_snapshot') as snapshot, \
                patch.object(f29,'owned_sql',return_value='f') as sql:
            with self.assertRaisesRegex(SystemExit,'existing auth session table'): f29.auth_sessions_prepare()
            self.assertEqual([], [call for call in sql.call_args_list if 'input_text' in call.kwargs])
            snapshot.assert_not_called(); self.assertFalse((Path(directory)/f29.AUTH_SESSIONS_RECEIPT).exists())

    def test_given_changed_v4_bytes__when_reading_the_auth_ddl__then_refuse_without_exposing_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()/'backend-app'; root.mkdir()
            ddl=root.parent/f29.AUTH_SESSIONS_DDL; ddl.parent.mkdir(parents=True); ddl.write_text('-- private mutation\n')
            with patch.object(f29,'ROOT',root):
                with self.assertRaisesRegex(SystemExit,'V4 DDL differs') as failure: f29.auth_sessions_ddl()
            self.assertNotIn('private',str(failure.exception))

    def test_given_the_reviewed_v4_bytes__when_reading_the_auth_ddl__then_return_them(self):
        self.assertIn('CREATE TABLE auth_sessions (',f29.auth_sessions_ddl())

    def test_given_auth_pg_failure_or_incomplete_acl__when_restoring__then_require_identity_revoke_only_seven_new_rights_and_revalidate_admin(self):
        for identity_ok in (True,False):
            with self.subTest(identity_ok=identity_ok), patch.object(f29.f2,'require_hosted'), \
                    patch.object(f29,'auth_sessions_require_identity',return_value=OWNER,side_effect=None if identity_ok else SystemExit('owner mismatch')), \
                    patch.object(f29,'auth_sessions_require_fixture',side_effect=AssertionError('new strict must not precede recovery')), \
                    patch.object(f29,'member_database_snapshot',return_value={'all24':'unchanged'}) as snapshot, \
                    patch.object(f29,'admin_update_require_fixture',return_value=OWNER) as old, patch.object(f29,'owned_sql') as sql:
                if not identity_ok:
                    with self.assertRaisesRegex(SystemExit,'owner mismatch'): f29.auth_sessions_restore()
                    sql.assert_not_called(); snapshot.assert_not_called(); old.assert_not_called()
                    continue
                f29.auth_sessions_restore()
                sql.assert_called_once_with(input_text='BEGIN;\n'+f29.AUTH_SESSIONS_REVOKES+'\nCOMMIT;\n')
                old.assert_called_once_with(); self.assertEqual(2,snapshot.call_count)
                self.assertEqual(1,f29.AUTH_SESSIONS_REVOKES.count('REVOKE '))
                self.assertIn('DROP TABLE IF EXISTS pawbridge_user.auth_sessions;',f29.AUTH_SESSIONS_REVOKES)
                for forbidden in ('DELETE','SELECT(user_id)','nickname','UPDATE(role','UPDATE(care_reg_no','GRANT ','SEQUENCE','INSERT'):
                    self.assertNotIn(forbidden,f29.AUTH_SESSIONS_REVOKES)


if __name__ == '__main__':
    print('F29 fixture guards: mocked resources; immutable inputs use read-only Git objects. No actual PG/JAR/SSE evidence.', flush=True)
    unittest.main()
