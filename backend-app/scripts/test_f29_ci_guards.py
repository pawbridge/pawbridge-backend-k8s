"""F29 fixture/XML controls; added member issuer guards inspect Java source only.

The eventual unit run mocks SQL and subprocess boundaries. Pinned source input loading
uses read-only Git objects, not legacy working-tree paths. These guards do not prove
PostgreSQL syntax/ACL/rollback, original JpaTM, actual XML execution or SSE/JAR behavior.
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
import xml.etree.ElementTree as ET

import f29_ci as f29
import prepare_f29_legacy_oracle as oracle

READ_ONLY_GIT = subprocess.check_output
HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
OWNER = '123-1'
SEOUL_METHOD = 'givenSyntheticTimestamp'
MEASURED_XML = {f29.TASK: {f29.PG_CLASS: 2, f29.MVC_CLASS: 2}, f29.SEOUL_TASK: {f29.PG_CLASS: 1}}
# Copy these existing inputs into a minimal checkout. No standalone F29 SQL file exists; only five pinned original report fragments are assembled.
# Original Java/migration inputs remain immutable Git objects at oracle.SOURCE.
MINIMAL_CHECKOUT_INPUTS = tuple('backend-app/scripts/f' + str(stage) + '_ci.py' for stage in range(2, 30)) + (
    'backend-app/scripts/prepare_f26_legacy_oracle.py',
    'backend-app/scripts/prepare_f27_legacy_oracle.py',
    'backend-app/scripts/prepare_f28_legacy_oracle.py',
    'backend-app/scripts/prepare_f29_legacy_oracle.py', 'backend-app/scripts/test_f29_ci_guards.py',
    'backend-app/scripts/f2_fixture.sql', 'backend-app/scripts/f3_fixture.sql',
    'backend-app/scripts/f4_fixture.sql', 'backend-app/scripts/f5_fixture.sql',
    'backend-app/scripts/f7_fixture.sql', 'backend-app/scripts/f8_fixture.sql',
    'backend-app/scripts/f9_fixture.sql', 'backend-app/scripts/f10_fixture.sql',
    'backend-app/scripts/f11_fixture.sql', 'backend-app/scripts/f12_fixture.sql',
    'backend-app/scripts/f13_fixture.sql', 'backend-app/scripts/f14_fixture.sql',
    'backend-app/scripts/f15_fixture.sql', 'backend-app/scripts/f16_fixture.sql',
    'backend-app/scripts/f17_fixture.sql')

MEMBER_PG_SOURCE = ('backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/'
                    'PostgresqlNoteSendCompatibilityTest.java')
MINIMAL_CHECKOUT_INPUTS += (MEMBER_PG_SOURCE,)


def pinned_sources():
    """Capture real fixed bytes before a shared subprocess mock; never call a mocked loader."""
    result = {}
    for source, expected in oracle.EXPECTED.items():
        raw = READ_ONLY_GIT(['git', 'show', oracle.SOURCE + ':' + source], cwd=f29.ROOT.parent,
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


def oracle_evidence(root, sources):
    """Real pinned source/shared bytes; controlled output bytes test receipt identities only."""
    entries = ['source_sha=' + f29.SOURCE]
    entries.extend(digest + '  source ' + source for source, digest in oracle.EXPECTED.items())
    for source, relative in oracle.SHARED.items():
        target = root.parent / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(sources[source])
        entries.append(oracle.EXPECTED[source] + '  shared-byte-identity ' + relative)
    advice = sources[oracle.ADVICE].decode('utf-8')
    annotations = oracle.checked_section(advice, oracle.ADVICE_ANNOTATIONS)
    adapted = advice.replace(annotations, oracle.F18_ADAPTED_ANNOTATIONS, 1).encode('utf-8')
    if hashlib.sha256(adapted).hexdigest() != oracle.F18_ADAPTED_SHA256:
        raise AssertionError('Fixed adapted advice test input differs')
    target = root.parent / oracle.F18_ADAPTED_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(adapted)
    entries.append(oracle.F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + oracle.F18_ADAPTED_PATH)
    for relative in oracle.OUTPUTS:
        raw = ('synthetic identity output only: ' + relative).encode('utf-8')
        target = root.parent / oracle.OUTPUT_ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        entries.append(hashlib.sha256(raw).hexdigest() + '  generated ' + relative)
    target = root.parent / oracle.EVIDENCE_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('\n'.join(entries) + '\n')
    return target


def xml_inputs(root):
    for task, classes in MEASURED_XML.items():
        directory = root / 'boot/build/test-results' / task
        directory.mkdir(parents=True)
        for name, count in classes.items():
            suite = ET.Element('testsuite', name=name, tests=str(count), failures='0', errors='0', skipped='0')
            for index in range(count):
                ET.SubElement(suite, 'testcase', name=SEOUL_METHOD + '()' if task == f29.SEOUL_TASK else 'synthetic-' + str(index), classname=name)
            ET.ElementTree(suite).write(directory / ('TEST-' + name + '.xml'))


class F29FixtureGuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_fixture_or_xml_actions__then_refuse_before_sql_git_or_prior_stage(self):
        actions = (f29.require_owner, f29.require_fixture, f29.verified_sources, f29.prepare,
                   f29.database_snapshot, f29.require_requests_fixture, f29.require_reports_fixture, f29.require_absent_reports, f29.require_measurements, f29.require_oracle_receipt,
                   f29.verify_results, lambda: f29.owned_sql('SELECT true'), lambda: f29.require_prior_receipts(OWNER))
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

    def test_given_sparse_checkout_without_legacy_directories__when_verifying_source_inputs__then_read_all_twenty_two_pinned_objects_including_stream_and_two_report_migrations(self):
        sources = pinned_sources()  # Real bytes loaded before shared subprocess patch.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; root.mkdir()
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(sources)) as git:
                self.assertEqual(sources, f29.verified_sources())
            self.assertEqual(22, git.call_count)
            self.assertFalse((root.parent / 'community-service').exists()); self.assertFalse((root.parent / 'user-service').exists())
            self.assertIn(f29.STREAM_SOURCE, sources)

    def test_given_tampered_legacy_or_new_stream_bytes__when_verifying_sources__then_reject_without_writes(self):
        sources = pinned_sources()
        for source in (oracle.SERVICE, f29.STREAM_SOURCE):
            bad = dict(sources); bad[source] += b'\nchanged'
            with self.subTest(source=source), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f29.subprocess, 'check_output', side_effect=source_git(bad)), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum'): f29.verified_sources()
                write.assert_not_called()

    def test_given_missing_stream_wrong_source_or_unavailable_object__when_verifying_sources__then_fail_closed_without_private_git_errors(self):
        bad = dict(oracle.EXPECTED); bad.pop(f29.STREAM_SOURCE)
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(oracle, 'EXPECTED', bad), \
                patch.object(f29.subprocess, 'check_output') as git:
            with self.assertRaisesRegex(SystemExit, 'twenty-two-source'): f29.verified_sources()
            git.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(oracle, 'SOURCE', 'wrong'), \
                patch.object(f29.subprocess, 'check_output') as git:
            with self.assertRaises(SystemExit): f29.verified_sources()
            git.assert_not_called()
        failure = subprocess.CalledProcessError(1, ['private-git-command'], output='private-value', stderr='private-error')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29.subprocess, 'check_output', side_effect=failure):
            with self.assertRaises(SystemExit) as actual: f29.verified_sources()
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

    def test_given_complete_f28_and_fixed_twenty_two_sources__when_preparing__then_last_old_strict_precedes_only_atomic_report_ddl_and_send_grants_and_new_strict(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
            f29.prepare()
            grant = next(event for event in events if event.startswith('BEGIN;'))
            self.assertEqual(['old-full'] + ['source'] * 22 + ['prior17', 'absence', grant, 'new-full', 'new18'], events)
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

    def test_given_unmeasured_counts_or_wrong_exact_class_set__when_requesting_results__then_stop_before_identity_or_oracle_io(self):
        bad_reports = ({f29.TASK: {f29.PG_CLASS: None, f29.MVC_CLASS: None}, f29.SEOUL_TASK: {f29.PG_CLASS: None}},
                       {f29.TASK: {f29.PG_CLASS: True, f29.MVC_CLASS: 2}, f29.SEOUL_TASK: {f29.PG_CLASS: 1}},
                       {f29.TASK: {'foreign': 2}, f29.SEOUL_TASK: {f29.PG_CLASS: 1}},
                       {f29.TASK: {f29.PG_CLASS: 2, f29.MVC_CLASS: 2}, f29.SEOUL_TASK: {f29.MVC_CLASS: 1}},
                       {**MEASURED_XML, 'foreign-task': {f29.PG_CLASS: 1}})
        for reports in bad_reports:
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'REPORTS', reports), \
                    patch.object(f29, 'require_owner') as owner, patch.object(f29, 'require_oracle_receipt') as receipt:
                with self.assertRaisesRegex(SystemExit, 'unmeasured'): f29.verify_results()
                owner.assert_not_called(); receipt.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'REPORTS', MEASURED_XML), patch.object(f29, 'SEOUL_METHOD', SEOUL_METHOD), \
                patch.object(f29, 'COUNTERS', ()), patch.object(f29, 'EXPECTED_JAR_COUNTS', None):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f29.require_measurements('jar')

    def test_given_exact_source_shared_and_controlled_output_receipt__when_validating_xml__then_only_exact_utc_and_one_named_seoul_class_pass(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; xml_inputs(root); oracle_evidence(root, sources)
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root), patch.object(f29, 'EVIDENCE', root / 'build/ci'), \
                    patch.object(f29, 'REPORTS', MEASURED_XML), patch.object(f29, 'SEOUL_METHOD', SEOUL_METHOD), patch.object(f29, 'require_owner', return_value=OWNER):
                f29.verify_results()
            value = json.loads((root / 'build/ci/f29-results.json').read_text())
            self.assertEqual({f29.TASK, f29.SEOUL_TASK}, set(value)); self.assertEqual({f29.PG_CLASS, f29.MVC_CLASS}, set(value[f29.TASK])); self.assertEqual(2, value[f29.TASK][f29.PG_CLASS]['tests'])

    def test_given_missing_or_wrong_seoul_method__when_gating_or_reading_xml__then_refuse_without_a_success_receipt(self):
        for method in (None, '', 'foreign-method'):
            with self.subTest(method=method), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f29, 'REPORTS', MEASURED_XML), patch.object(f29, 'SEOUL_METHOD', method), \
                    patch.object(f29, 'require_owner') as owner, patch.object(f29, 'require_oracle_receipt') as oracle_io:
                with self.assertRaisesRegex(SystemExit, 'remain unmeasured'): f29.verify_results()
                owner.assert_not_called(); oracle_io.assert_not_called()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; xml_inputs(root)
            target = root / 'boot/build/test-results' / f29.SEOUL_TASK / ('TEST-' + f29.PG_CLASS + '.xml')
            tree = ET.parse(target); tree.getroot()[0].set('name', 'givenDifferentTimestamp()'); tree.write(target)
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root), patch.object(f29, 'EVIDENCE', root / 'build/ci'), \
                    patch.object(f29, 'REPORTS', MEASURED_XML), patch.object(f29, 'SEOUL_METHOD', SEOUL_METHOD), \
                    patch.object(f29, 'require_owner', return_value=OWNER), patch.object(f29, 'require_oracle_receipt'):
                with self.assertRaisesRegex(SystemExit, 'only its exact measured timestamp method'): f29.verify_results()
            self.assertFalse((root / 'build/ci/f29-results.json').exists())

    def test_given_tampered_source_shared_adapted_generated_or_redirected_receipt__when_checking_oracle_identity__then_refuse(self):
        sources = pinned_sources()
        for phase in ('source', 'duplicate', 'shared', 'adapted', 'generated', 'redirected'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve() / 'backend-app'; receipt = oracle_evidence(root, sources)
                if phase == 'source': receipt.write_text(receipt.read_text().replace(oracle.EXPECTED[f29.STREAM_SOURCE], '0' * 64, 1))
                elif phase == 'duplicate': receipt.write_text(receipt.read_text() + next(line for line in receipt.read_text().splitlines() if '  source ' in line) + '\n')
                elif phase == 'shared': (root.parent / next(iter(oracle.SHARED.values()))).write_bytes(b'changed')
                elif phase == 'adapted': (root.parent / oracle.F18_ADAPTED_PATH).write_bytes(b'changed')
                elif phase == 'generated': (root.parent / oracle.OUTPUT_ROOT / oracle.OUTPUTS[0]).write_bytes(b'changed')
                else:
                    value = receipt.read_bytes(); receipt.unlink(); alias = receipt.with_name('alias'); alias.write_bytes(value); receipt.symlink_to(alias)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root):
                    with self.assertRaises(SystemExit): f29.require_oracle_receipt()

    def test_given_wrong_xml_shape_fqn_count_or_hidden_unsuccessful_child__when_validating__then_no_success_receipt(self):
        defects = ('extra', 'missing', 'malformed', 'tag', 'suite', 'count', 'children', 'nested-case', 'child-fqn',
                   'failure', 'error', 'skipped', 'disabled', 'attribute-failure')
        for defect in defects:
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve() / 'backend-app'; xml_inputs(root)
                task = f29.TASK
                parent = root / 'boot/build/test-results' / task; target = parent / ('TEST-' + f29.PG_CLASS + '.xml')
                tree = ET.parse(target); suite = tree.getroot()
                if defect == 'extra': (parent / 'TEST-extra.xml').write_bytes(target.read_bytes())
                elif defect == 'missing': target.unlink()
                elif defect == 'malformed': target.write_text('<invalid')
                else:
                    if defect == 'tag': suite.tag = 'testsuites'
                    elif defect == 'suite': suite.set('name', 'foreign')
                    elif defect == 'count': suite.set('tests', '0')
                    elif defect == 'children': suite.remove(suite[0])
                    elif defect == 'nested-case': ET.SubElement(ET.SubElement(suite, 'nested'), 'testcase', classname=f29.PG_CLASS)
                    elif defect == 'child-fqn': suite[0].set('classname', 'foreign')
                    elif defect == 'disabled': suite.set('disabled', '1')
                    elif defect == 'attribute-failure': suite.set('failures', '1')
                    else: ET.SubElement(suite[0], defect)
                    tree.write(target)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f29, 'ROOT', root), patch.object(f29, 'EVIDENCE', root / 'build/ci'), \
                        patch.object(f29, 'REPORTS', MEASURED_XML), patch.object(f29, 'SEOUL_METHOD', SEOUL_METHOD), \
                        patch.object(f29, 'require_owner', return_value=OWNER), patch.object(f29, 'require_oracle_receipt'):
                    with self.assertRaises(SystemExit): f29.verify_results()
                self.assertFalse((root / 'build/ci/f29-results.json').exists())




class MemberCleanupLateGuardTest(unittest.TestCase):
    """Changed late source/fixture controls only; mock SQL never proves PG or managers."""
    @staticmethod
    def sources():
        result={}
        for source,expected in {**oracle.EXPECTED,**oracle.MEMBER_INPUTS}.items():
            raw=READ_ONLY_GIT(['git','show',oracle.SOURCE+':'+source],cwd=f29.ROOT.parent,stderr=subprocess.PIPE)
            if hashlib.sha256(raw).hexdigest()!=expected:raise AssertionError('Fixed member source input differs')
            result[source]=raw
        return result

    @staticmethod
    def inputs(directory,sources,canonical=False):
        repository=Path(directory).resolve();root=repository/'backend-app'
        oracle_evidence(root,sources)
        for source,relative in oracle.MEMBER_SHARED.items():
            target=repository/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(sources[source])
        if canonical:
            # Only temporary controlled files: produce the actual immutable receipt rather than
            # accepting the old test's deliberately synthetic output-identity placeholders.
            for relative in oracle.OUTPUTS:(repository/oracle.OUTPUT_ROOT/relative).unlink()
            (repository/oracle.EVIDENCE_PATH).unlink()
            with patch.object(oracle.subprocess,'check_output',side_effect=source_git(sources)):
                oracle.extract(repository,repository/oracle.OUTPUT_ROOT,repository/oracle.EVIDENCE_PATH)
                oracle.extract_member_cleanup(repository,repository/oracle.OUTPUT_ROOT,repository/oracle.MEMBER_EVIDENCE)
        return repository,repository/oracle.OUTPUT_ROOT,repository/oracle.MEMBER_EVIDENCE

    def test_given_last_member_issuer__when_checking_optional_file_creation__then_only_selected_scope_can_create_private_owned_tokens(self):
        # Source-wiring regression only: this does not execute Java or prove filesystem/PG behavior.
        source=(Path(__file__).resolve().parents[2]/MEMBER_PG_SOURCE).read_text()
        start=source.index(' void givenAllMemberCasesSucceededAndOwnedRowsRestored__whenRefreshingOnlyThreeUsedPrincipalsLast__')
        method=source[start:source.index('\n private final class MemberCleanupRound',start)]
        expected_path='assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));'
        create_branch=method[method.index('  if(create){'):method.index('  }else{')]
        self.assertIn('requireMemberFixture();returned();assertThat(successfulMemberCases.get()).isEqualTo(11)',method)
        self.assertLess(method.index('String before=memberSnapshot();'),method.index(expected_path))
        self.assertIn('boolean create=!Files.exists(path,LinkOption.NOFOLLOW_LINKS);',method)
        self.assertIn('assertThat(System.getenv("CONTRACT_PROFILE")).isEqualTo("member-management");',create_branch)
        self.assertIn('assertThat(Files.isSymbolicLink(parent)).isFalse()',create_branch)
        self.assertIn('assertThat(Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)).isTrue()',create_branch)
        self.assertIn('assertThat(parent.toRealPath()).isEqualTo(parent)',create_branch)
        self.assertIn('Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))',method)
        self.assertNotIn('Files.createDirectories',method)
        self.assertNotIn('Files.setPosixFilePermissions',method)
        self.assertLess(method.index('memberSnapshot().equals(before)'),method.index('Files.newByteChannel'))

    def test_given_existing_member_token_file__when_checking_final_issuer__then_keep_prior_keys_roles_and_full_file_safety(self):
        source=(Path(__file__).resolve().parents[2]/MEMBER_PG_SOURCE).read_text()
        start=source.index(' void givenAllMemberCasesSucceededAndOwnedRowsRestored__whenRefreshingOnlyThreeUsedPrincipalsLast__')
        method=source[start:source.index('\n private final class MemberCleanupRound',start)]
        existing=method[method.index('  }else{'):method.index('  var mapper=')]
        for check in ('Files.isSymbolicLink(path)', 'Files.isRegularFile(path)', 'path.toRealPath()',
                      'Files.getPosixFilePermissions(path)'):
            self.assertIn(check,existing)
        self.assertIn('if(!create)assertThat(tokens.keySet()).containsAll(ids.keySet())',method)
        self.assertIn('ids.put("F19_ADMIN",9_973_001L);ids.put("F29_CALLER",9_983_001L);ids.put("F29_OTHER",9_983_002L)',method)
        self.assertIn('entry.getKey().equals("F19_ADMIN")?Role.ROLE_ADMIN:Role.ROLE_USER',method)
        self.assertIn('new JwtProvider(secret,600_000,600_000)',method)
        self.assertIn('claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(600_000L)',method)
        self.assertIn('Objects.equals(tokens.get(entry.getKey()),entry.getValue())',method)
        self.assertIn('containsExactlyInAnyOrderElementsOf(create?ids.keySet():prior.keySet())',method)
        self.assertIn('Files.writeString(path,mapper.writeValueAsString(tokens),StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)',method)

    def test_given_fixed_shared_inputs__when_explicitly_extending_member_source__then_add_only_raw5_wrapper1_and_keep_default_generated3_receipt_bytes(self):
        sources=self.sources()
        with tempfile.TemporaryDirectory() as directory:
            repository,output,evidence=self.inputs(directory,sources)
            before={(output/p): (output/p).read_bytes() for p in oracle.OUTPUTS}
            base=(repository/oracle.EVIDENCE_PATH).read_bytes()
            with patch.object(oracle.subprocess,'check_output',side_effect=source_git(sources)):
                oracle.extract_member_cleanup(repository,output,evidence)
                oracle.extract_member_cleanup(repository,output,evidence)
            self.assertEqual(base,(repository/oracle.EVIDENCE_PATH).read_bytes())
            for p,raw in before.items():self.assertEqual(raw,p.read_bytes())
            for source in oracle.MEMBER_RAW:self.assertEqual(sources[source],(output/source.split('/src/main/java/',1)[1]).read_bytes())
            wrapper=(output/oracle.MEMBER_WRAPPER).read_text()
            for unit in oracle.MEMBER_UNITS:self.assertIn(oracle.checked_method(sources[oracle.SERVICE].decode(),unit),wrapper)
            self.assertEqual(6,len([p for p in output.rglob('*.java') if p not in before]))
            self.assertIn('base-F29-receipt',evidence.read_text())

    def test_given_source_shared_receipt_or_fqn_conflict__when_extending_member_source__then_refuse_every_new_write(self):
        original=self.sources()
        for defect in ('source','shared','receipt','fqn'):
            with self.subTest(defect=defect),tempfile.TemporaryDirectory() as directory:
                sources=dict(original);repository,output,evidence=self.inputs(directory,sources)
                if defect=='source':sources[oracle.MEMBER_RAW[0]]=b'tampered'
                elif defect=='shared':(repository/next(iter(oracle.MEMBER_SHARED.values()))).write_bytes(b'tampered')
                elif defect=='receipt':evidence.parent.mkdir(parents=True,exist_ok=True);evidence.write_text('foreign receipt')
                else:
                    other=repository/'backend-app/boot/src/compatibilityTest/java/Conflict.java';other.parent.mkdir(parents=True,exist_ok=True)
                    other.write_text('package com.pawbridge.communityservice.contact; class F29LegacyMemberCleanupService {}')
                with patch.object(oracle.subprocess,'check_output',side_effect=source_git(sources)):
                    with self.assertRaises(SystemExit):oracle.extract_member_cleanup(repository,output,evidence)
                self.assertEqual(3,len(list(output.rglob('*.java'))))
                self.assertFalse((output/oracle.MEMBER_WRAPPER).exists())

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
        sources=self.sources()
        with tempfile.TemporaryDirectory() as directory:
            repository,_,evidence=self.inputs(directory,sources,canonical=True)
            root=repository/'backend-app'
            with patch.object(f29,'ROOT',root),patch.object(f29,'EVIDENCE',evidence.parent),patch.object(f29.f2,'require_hosted',return_value=OWNER),patch.object(f29,'require_prior_receipts') as prior,patch.object(f29.f28,'require_owner') as old,patch.object(f29.f21,'check_receipt') as receipt,patch.object(f29,'owned_sql') as sql:
                self.assertEqual(OWNER,f29.member_require_identity())
            prior.assert_called_once_with(OWNER);old.assert_not_called();sql.assert_not_called()
            self.assertEqual([evidence.parent/'f29-fixture-write.txt',evidence.parent/f29.MEMBER_RECEIPT],[call.args[0] for call in receipt.call_args_list])

    def test_given_missing_changed_redirected_oracle_or_changed_artifacts__when_checking_member_identity__then_reject_without_sql_or_private_output(self):
        sources=self.sources()
        for defect in ('missing','source','shared','generated','base','base-generated','adapted','redirected'):
            with self.subTest(defect=defect),tempfile.TemporaryDirectory() as directory:
                repository,output,evidence=self.inputs(directory,sources,canonical=True)
                if defect=='missing':evidence.unlink()
                elif defect=='source':evidence.write_bytes(evidence.read_bytes().replace(f29.SOURCE.encode(),b'0'*40,1))
                elif defect=='shared':(repository/next(iter(oracle.MEMBER_SHARED.values()))).write_bytes(b'private source mutation')
                elif defect=='generated':(output/oracle.MEMBER_WRAPPER).write_bytes(b'private generated mutation')
                elif defect=='base':(repository/oracle.EVIDENCE_PATH).write_bytes(b'private base receipt mutation')
                elif defect=='base-generated':(output/oracle.OUTPUTS[0]).write_bytes(b'private base generated mutation')
                elif defect=='adapted':(repository/oracle.F18_ADAPTED_PATH).write_bytes(b'private adapted mutation')
                else:
                    content=evidence.read_bytes();evidence.unlink();alias=evidence.with_name('alias-member-oracle');alias.write_bytes(content);evidence.symlink_to(alias)
                with patch.object(f29,'ROOT',repository/'backend-app'),patch.object(f29,'EVIDENCE',evidence.parent),patch.object(f29.f2,'require_hosted',return_value=OWNER),patch.object(f29,'require_prior_receipts'),patch.object(f29.f21,'check_receipt'),patch.object(f29,'owned_sql') as sql:
                    with self.assertRaisesRegex(SystemExit,'Member cleanup oracle receipt or source artifacts differ') as failure:
                        f29.member_require_identity()
                self.assertNotIn('private',str(failure.exception));self.assertTrue(failure.exception.__suppress_context__)
                self.assertIsNone(failure.exception.__cause__);sql.assert_not_called()

    def test_given_complete_old_fixture__when_late_member_prepare_runs__then_old_strict_once_precedes_only_atomic_extension_and_new24_checks(self):
        sources=self.sources();events=[]
        with tempfile.TemporaryDirectory() as directory:
            repository,_,extra=self.inputs(directory,sources,canonical=True)
            evidence=extra.parent;before={key:[] for key,_,_ in f29.SNAPSHOT_TABLES}
            validate=f29.member_require_oracle_receipt
            def validate_evidence():
                validate();events.append('oracle')
            with patch.object(f29,'ROOT',repository/'backend-app'),patch.object(f29.f2,'require_hosted',return_value=OWNER),patch.object(f29,'require_fixture',side_effect=lambda:events.append('old') or OWNER),patch.object(f29,'EVIDENCE',evidence),patch.object(f29,'member_require_oracle_receipt',side_effect=validate_evidence),patch.object(f29.subprocess,'check_output',side_effect=source_git(sources)),patch.object(f29,'owned_sql',side_effect=lambda query='',**kw:events.append('write' if 'input_text' in kw else 'absence') or 't'),patch.object(f29,'database_snapshot',return_value=before),patch.object(f29,'member_require_fixture',side_effect=lambda:events.append('new') or OWNER),patch.object(f29,'member_database_snapshot',return_value=dict(before,**{key:[] for key,_,_ in f29.MEMBER_TABLES})):
                f29.member_cleanup_prepare()
            self.assertEqual(['old','oracle'],events[:2]);self.assertEqual(1,events.count('old'));self.assertEqual(1,events.count('write'))
            self.assertLess(events.index('write'),events.index('new'))
            self.assertEqual(f29.member_fixture_proof(OWNER),(evidence/f29.MEMBER_RECEIPT).read_text())

    def test_given_missing_or_changed_oracle_evidence__when_preparing_member_cleanup__then_reject_before_any_ddl_or_grant(self):
        sources=self.sources()
        for defect in ('missing','receipt','generated'):
            with self.subTest(defect=defect),tempfile.TemporaryDirectory() as directory:
                repository,output,evidence=self.inputs(directory,sources,canonical=True)
                if defect=='missing':evidence.unlink()
                elif defect=='receipt':evidence.write_bytes(b'private receipt mutation')
                else:(output/oracle.MEMBER_WRAPPER).write_bytes(b'private generated mutation')
                with patch.object(f29,'ROOT',repository/'backend-app'),patch.object(f29,'EVIDENCE',evidence.parent),patch.object(f29.f2,'require_hosted',return_value=OWNER),patch.object(f29,'require_fixture',return_value=OWNER) as old,patch.object(f29,'require_prior_receipts'),patch.object(f29.f21,'check_receipt'),patch.object(f29,'member_verified_ddl',return_value='BEGIN;\nCOMMIT;\n') as ddl,patch.object(f29,'database_snapshot',return_value={}) as snapshot,patch.object(f29,'member_require_fixture',side_effect=f29.member_require_identity),patch.object(f29,'owned_sql',return_value='t') as sql:
                    with self.assertRaisesRegex(SystemExit,'Member cleanup oracle receipt or source artifacts differ') as failure:
                        f29.member_cleanup_prepare()
                self.assertEqual([], [call for call in sql.call_args_list if 'input_text' in call.kwargs])
                sql.assert_not_called();ddl.assert_not_called();snapshot.assert_not_called();old.assert_called_once_with()
                self.assertFalse((evidence.parent/f29.MEMBER_RECEIPT).exists())
                self.assertNotIn('private',str(failure.exception));self.assertTrue(failure.exception.__suppress_context__)
                self.assertIsNone(failure.exception.__cause__)



class AuthSessionsFixtureGuardTest(unittest.TestCase):
    def test_given_changed_auth_source_receipt__when_preparing__then_reject_before_grant_snapshot_or_new_receipt(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f29,'EVIDENCE',Path(directory).resolve()), \
                patch.object(f29.f2,'require_hosted'), patch.object(f29,'admin_update_require_fixture',return_value=OWNER), \
                patch.object(f29,'auth_sessions_require_oracle_receipt',side_effect=SystemExit('source mismatch')), \
                patch.object(f29,'owned_sql') as sql, patch.object(f29,'member_database_snapshot') as snapshot:
            with self.assertRaisesRegex(SystemExit,'source mismatch'): f29.auth_sessions_prepare()
            sql.assert_not_called(); snapshot.assert_not_called(); self.assertFalse((Path(directory)/f29.AUTH_SESSIONS_RECEIPT).exists())

    def test_given_owned_old_acl__when_extending_then_denying__then_old_strict_runs_once_and_identity_restores_exact_rights(self):
        events=[]
        with tempfile.TemporaryDirectory() as directory, patch.object(f29,'EVIDENCE',Path(directory).resolve()), \
                patch.object(f29.f2,'require_hosted'), patch.object(f29,'admin_update_require_fixture',side_effect=lambda:events.append('old') or OWNER) as old, \
                patch.object(f29,'auth_sessions_require_oracle_receipt',side_effect=lambda:events.append('source')), \
                patch.object(f29,'auth_sessions_require_fixture',side_effect=lambda:events.append('new') or OWNER), \
                patch.object(f29,'auth_sessions_require_identity',side_effect=lambda:events.append('identity') or OWNER), \
                patch.object(f29,'member_database_snapshot',return_value={'all24':'same'}), \
                patch.object(f29,'owned_sql',side_effect=lambda query='',input_text=None:events.append(input_text or query) or ''):
            f29.auth_sessions_prepare()
            self.assertEqual(f29.auth_sessions_fixture_proof(OWNER),(Path(directory)/f29.AUTH_SESSIONS_RECEIPT).read_text())
            self.assertEqual(1,events.count('old')); self.assertLess(events.index('source'),next(i for i,e in enumerate(events) if 'GRANT ' in e))
            old.reset_mock()
            with self.assertRaisesRegex(RuntimeError,'original failure'):
                with f29.auth_sessions_denied_privilege('rotate-write'): raise RuntimeError('original failure')
            old.assert_not_called()
            self.assertEqual('GRANT UPDATE(token) ON pawbridge_user.refresh_tokens TO pawbridge_f3_writer',events[-2])
            self.assertEqual('new',events[-1]); self.assertIn('identity',events)



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
                self.assertEqual(2,f29.AUTH_SESSIONS_REVOKES.count('REVOKE '))
                for forbidden in ('DELETE','SELECT(user_id)','nickname','UPDATE(role','UPDATE(care_reg_no','GRANT ','SEQUENCE','INSERT'):
                    self.assertNotIn(forbidden,f29.AUTH_SESSIONS_REVOKES)


if __name__ == '__main__':
    print('F29 fixture/XML guards: mocked resources; immutable inputs use read-only Git objects. No actual PG/JAR/SSE evidence.', flush=True)
    unittest.main()
