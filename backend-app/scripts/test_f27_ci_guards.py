"""F27 fixture/XML controls only; no guard has been executed at private handoff.

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

import f27_ci as f27
import prepare_f27_legacy_oracle as oracle

READ_ONLY_GIT = subprocess.check_output
HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
OWNER = '123-1'
MEASURED_XML = {f27.TASK: {f27.PG_CLASS: 2, f27.MVC_CLASS: 2}}
# Copy these existing inputs into a minimal checkout. No new F27 SQL fixture exists.
# Original Java/migration inputs remain immutable Git objects at oracle.SOURCE.
MINIMAL_CHECKOUT_INPUTS = tuple('backend-app/scripts/f' + str(stage) + '_ci.py' for stage in range(2, 28)) + (
    'backend-app/scripts/prepare_f26_legacy_oracle.py',
    'backend-app/scripts/prepare_f27_legacy_oracle.py', 'backend-app/scripts/test_f27_ci_guards.py',
    'backend-app/scripts/f2_fixture.sql', 'backend-app/scripts/f3_fixture.sql',
    'backend-app/scripts/f4_fixture.sql', 'backend-app/scripts/f5_fixture.sql',
    'backend-app/scripts/f7_fixture.sql', 'backend-app/scripts/f8_fixture.sql',
    'backend-app/scripts/f9_fixture.sql', 'backend-app/scripts/f10_fixture.sql',
    'backend-app/scripts/f11_fixture.sql', 'backend-app/scripts/f12_fixture.sql',
    'backend-app/scripts/f13_fixture.sql', 'backend-app/scripts/f14_fixture.sql',
    'backend-app/scripts/f15_fixture.sql', 'backend-app/scripts/f16_fixture.sql',
    'backend-app/scripts/f17_fixture.sql')


def pinned_sources():
    """Capture real fixed bytes before a shared subprocess mock; never call a mocked loader."""
    result = {}
    for source, expected in oracle.EXPECTED.items():
        raw = READ_ONLY_GIT(['git', 'show', oracle.SOURCE + ':' + source], cwd=f27.ROOT.parent,
                            stderr=subprocess.PIPE)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise AssertionError('F27 fixed guard input checksum differs')
        result[source] = raw
    return result


def source_git(sources, events=None):
    def read(args, **kwargs):
        if args[:2] != ['git', 'show'] or not args[2].startswith(f27.SOURCE + ':'):
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
        for item in (patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'ROOT', root),
                patch.object(f27, 'EVIDENCE', evidence),
                patch.object(f27.f26, 'require_fixture', side_effect=lambda: events.append('old-full') or OWNER),
                patch.object(f27.subprocess, 'check_output', side_effect=source_git(sources, events)),
                patch.object(f27, 'database_snapshot', side_effect=lambda: events.append('snapshot') or {'protected': []}),
                patch.object(f27, 'require_fixture', side_effect=lambda: events.append('new-full') or OWNER),
                patch.object(f27, 'owned_sql', side_effect=sql)):
            stack.enter_context(item)
        yield root, evidence, events


def oracle_evidence(root, sources):
    """Real pinned source/shared bytes; controlled output bytes test receipt identities only."""
    entries = ['source_sha=' + f27.SOURCE]
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
                ET.SubElement(suite, 'testcase', name='synthetic-' + str(index), classname=name)
            ET.ElementTree(suite).write(directory / ('TEST-' + name + '.xml'))


class F27FixtureGuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_fixture_or_xml_actions__then_refuse_before_sql_git_or_prior_stage(self):
        actions = (f27.require_owner, f27.require_fixture, f27.verified_sources, f27.prepare,
                   f27.database_snapshot, f27.require_measurements, f27.require_oracle_receipt,
                   f27.verify_results, lambda: f27.owned_sql('SELECT true'), lambda: f27.require_prior_receipts(OWNER))
        for action in actions:
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f27.f3, 'sql') as sql, patch.object(f27.subprocess, 'check_output') as git, \
                    patch.object(f27.f26, 'require_owner') as owner, patch.object(f27.f26, 'require_fixture') as strict:
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
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27.f3, 'sql', side_effect=failure):
                    with self.assertRaises(SystemExit) as actual:
                        f27.owned_sql('private-sql', input_text='private-body private-email@fixture.invalid')
                self.assertIs(type(actual.exception), SystemExit)
                self.assertEqual('F27 owned fixture SQL failed without exposing command, SQL or output', str(actual.exception))
                self.assertNotRegex(str(actual.exception), 'private-|CalledProcessError|TimeoutExpired')
                self.assertIsNone(actual.exception.__cause__)
                self.assertTrue(actual.exception.__suppress_context__)

    def test_given_extended_writer__when_checking_owner__then_keep_acl_safe_f26_owner_and_exact_new_receipt_without_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27.f26, 'require_owner', return_value=OWNER) as owner, \
                patch.object(f27.f26, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f27.f21, 'check_receipt') as receipt:
            self.assertEqual(OWNER, f27.require_owner())
        owner.assert_called_once()
        receipt.assert_called_once_with(f27.EVIDENCE / 'f27-fixture-write.txt', f27.fixture_proof(OWNER))

    def test_given_revoked_user_or_writer_acl__when_checking_prior_receipts__then_use_marker_identity_without_owner_or_old_strict(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27.f26, 'require_prior_receipts') as prior, \
                patch.object(f27.f26, 'require_owner', side_effect=AssertionError('User strict')), \
                patch.object(f27.f26, 'require_fixture', side_effect=AssertionError('old strict')), \
                patch.object(f27.f21, 'check_receipt') as receipt:
            f27.require_prior_receipts(OWNER)
        prior.assert_called_once_with(OWNER)
        receipt.assert_called_once_with(f27.EVIDENCE / 'f26-fixture-write.txt', f27.f26.fixture_proof(OWNER))

    def test_given_missing_foreign_or_redirected_new_receipt__when_checking_owner__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory).resolve(); target = evidence / 'f27-fixture-write.txt'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'EVIDENCE', evidence), \
                    patch.object(f27.f26, 'require_owner', return_value=OWNER):
                with self.assertRaises(SystemExit): f27.require_owner()
                target.write_text(f27.fixture_proof('foreign-owner'))
                with self.assertRaises(SystemExit): f27.require_owner()
                target.unlink(); elsewhere = evidence / 'elsewhere'; elsewhere.write_text(f27.fixture_proof(OWNER)); target.symlink_to(elsewhere)
                with self.assertRaises(SystemExit): f27.require_owner()

    def test_given_sparse_checkout_without_legacy_directories__when_verifying_source_inputs__then_read_all_twenty_pinned_objects_including_stream(self):
        sources = pinned_sources()  # Real bytes loaded before shared subprocess patch.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; root.mkdir()
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'ROOT', root), \
                    patch.object(f27.subprocess, 'check_output', side_effect=source_git(sources)) as git:
                self.assertEqual(sources, f27.verified_sources())
            self.assertEqual(20, git.call_count)
            self.assertFalse((root.parent / 'community-service').exists()); self.assertFalse((root.parent / 'user-service').exists())
            self.assertIn(f27.STREAM_SOURCE, sources)

    def test_given_tampered_legacy_or_new_stream_bytes__when_verifying_sources__then_reject_without_writes(self):
        sources = pinned_sources()
        for source in (oracle.SERVICE, f27.STREAM_SOURCE):
            bad = dict(sources); bad[source] += b'\nchanged'
            with self.subTest(source=source), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f27.subprocess, 'check_output', side_effect=source_git(bad)), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum'): f27.verified_sources()
                write.assert_not_called()

    def test_given_missing_stream_wrong_source_or_unavailable_object__when_verifying_sources__then_fail_closed_without_private_git_errors(self):
        bad = dict(oracle.EXPECTED); bad.pop(f27.STREAM_SOURCE)
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(oracle, 'EXPECTED', bad), \
                patch.object(f27.subprocess, 'check_output') as git:
            with self.assertRaisesRegex(SystemExit, 'twenty-source'): f27.verified_sources()
            git.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(oracle, 'SOURCE', 'wrong'), \
                patch.object(f27.subprocess, 'check_output') as git:
            with self.assertRaises(SystemExit): f27.verified_sources()
            git.assert_not_called()
        failure = subprocess.CalledProcessError(1, ['private-git-command'], output='private-value', stderr='private-error')
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27.subprocess, 'check_output', side_effect=failure):
            with self.assertRaises(SystemExit) as actual: f27.verified_sources()
        self.assertNotRegex(str(actual.exception), 'private-git|private-value|private-error')

    def test_given_five_inherited_tables__when_checking_catalog__then_enforce_exact_old_acl_and_only_one_new_favorite_column(self):
        queries = []
        with patch.object(f27, 'require_owner', return_value=OWNER), \
                patch.object(f27, 'catalog', side_effect=lambda query, stage: queries.append((query, stage)) or 't'), \
                patch.object(f27.f26, 'require_fixture', side_effect=AssertionError('old strict')):
            self.assertEqual(OWNER, f27.require_fixture())
        self.assertEqual(['shape', 'acl', 'constraints'] * 5, [stage for _, stage in queries])
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

    def test_given_rejected_shape_extra_acl_constraint_or_sequence_catalog_result__when_checking_fixture__then_stop_at_that_boundary(self):
        # Catalog booleans are controlled inputs, not an emulation of PostgreSQL privilege evaluation.
        for index in range(15):
            with self.subTest(index=index), patch.object(f27, 'require_owner', return_value=OWNER), \
                    patch.object(f27, 'catalog', side_effect=['t'] * index + ['f']) as catalog:
                with self.assertRaises(SystemExit): f27.require_fixture()
                self.assertEqual(index + 1, catalog.call_count)

    def test_given_non_catalog_statement_or_incomplete_snapshot__when_checking__then_refuse_without_sql_or_partial_snapshot(self):
        with patch.object(f27, 'owned_sql') as sql:
            for query, stage in (('DELETE FROM private_notes', 'acl'), ('SELECT true', 'business')):
                with self.assertRaises(ValueError): f27.catalog(query, stage)
            sql.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True):
            for value in ([], {str(index): [] for index in range(15)}):
                with patch.object(f27.f26, 'database_snapshot', return_value=value):
                    with self.assertRaises(SystemExit): f27.database_snapshot()

    def test_given_complete_f26_and_fixed_twenty_sources__when_preparing__then_last_old_strict_precedes_only_atomic_column_grants_and_new_strict(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
            f27.prepare()
            grant = next(event for event in events if event.startswith('BEGIN;'))
            self.assertEqual(['old-full'] + ['source'] * 20 + ['snapshot', grant, 'new-full', 'snapshot'], events)
            self.assertEqual('BEGIN;\nGRANT UPDATE(favorite) ON ' + f27.MAILBOXES + ' TO pawbridge_f3_writer;\nCOMMIT;\n', grant)
            self.assertEqual(f27.fixture_proof(OWNER), (evidence / 'f27-fixture-write.txt').read_text())
            self.assertNotIn('CREATE ', grant); self.assertNotIn('pawbridge_f2_reader', grant); self.assertNotIn('INSERT', grant)

    def test_given_old_stage_existing_receipt_source_snapshot_or_grant_failure__when_preparing__then_no_new_receipt_or_success_claim(self):
        sources = pinned_sources()
        for phase in ('old', 'existing', 'source', 'snapshot', 'grant'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, events):
                receipt = evidence / 'f27-fixture-write.txt'
                if phase == 'existing': receipt.write_text(f27.fixture_proof(OWNER))
                target = f27.f26 if phase == 'old' else f27
                attribute = {'old': 'require_fixture', 'source': 'verified_sources', 'snapshot': 'database_snapshot', 'grant': 'owned_sql'}.get(phase)
                output = io.StringIO()
                with ExitStack() as stack:
                    if attribute: stack.enter_context(patch.object(target, attribute, side_effect=SystemExit('controlled failure')))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f27.prepare()
                self.assertEqual(phase == 'existing', receipt.exists()); self.assertNotIn('Extended only', output.getvalue())
                if phase in ('old', 'existing', 'source', 'snapshot'):
                    self.assertFalse(any(event.startswith('BEGIN;') for event in events))

    def test_given_post_grant_catalog_or_business_snapshot_failure__when_finishing_prepare__then_preserve_owned_receipt_but_never_print_success(self):
        sources = pinned_sources()
        for phase in ('strict', 'rows'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, preparation(directory, sources) as (_, evidence, _):
                output = io.StringIO()
                with ExitStack() as stack:
                    if phase == 'strict': stack.enter_context(patch.object(f27, 'require_fixture', side_effect=SystemExit('catalog')))
                    else: stack.enter_context(patch.object(f27, 'database_snapshot', side_effect=[{}, {'changed': []}]))
                    with redirect_stdout(output), self.assertRaises(SystemExit): f27.prepare()
                self.assertTrue((evidence / 'f27-fixture-write.txt').is_file()); self.assertNotIn('Extended only', output.getvalue())

    def test_given_denial_body_failure__when_unwinding__then_restore_both_planned_roles_only_after_identity_receipt_and_before_full_check(self):
        for kind in ('favorite', 'nickname'):
            events = []
            with self.subTest(kind=kind), patch.object(f27, 'require_fixture', side_effect=lambda: events.append('strict') or OWNER), \
                    patch.object(f27, 'require_prior_receipts', side_effect=lambda owner: events.append('identity')), \
                    patch.object(f27.f21, 'check_receipt', side_effect=lambda *args: events.append('receipt')), \
                    patch.object(f27, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(AssertionError, 'body'):
                    with f27.denied_privilege(kind): raise AssertionError('body')
            self.assertEqual('strict', events[0]); self.assertTrue(events[1].startswith('REVOKE '))
            self.assertEqual(['identity', 'receipt'], events[2:4])
            self.assertEqual(events[1].replace('REVOKE ', 'GRANT ').replace(' FROM ', ' TO '), events[4]); self.assertEqual('strict', events[5])

    def test_given_foreign_owner_or_receipt_during_denial__when_restoring__then_never_grant_to_a_different_target(self):
        for phase in ('identity', 'receipt'):
            with self.subTest(phase=phase), patch.object(f27, 'require_fixture', return_value=OWNER), \
                    patch.object(f27, 'require_prior_receipts', side_effect=SystemExit('foreign') if phase == 'identity' else None), \
                    patch.object(f27.f21, 'check_receipt', side_effect=SystemExit('foreign') if phase == 'receipt' else None), \
                    patch.object(f27, 'owned_sql') as sql:
                with self.assertRaises(SystemExit):
                    with f27.denied_privilege('favorite'): pass
                self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_unknown_denial_failed_revoke_or_failed_grant__when_unwinding__then_never_run_body_or_claim_restored_acl(self):
        with patch.object(f27, 'require_fixture') as strict, patch.object(f27, 'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f27.denied_privilege('unplanned'): self.fail('unexpected body')
            strict.assert_not_called(); sql.assert_not_called()
        for phase in ('revoke', 'grant'):
            with self.subTest(phase=phase), patch.object(f27, 'require_fixture', return_value=OWNER) as strict, \
                    patch.object(f27, 'require_prior_receipts'), patch.object(f27.f21, 'check_receipt'), \
                    patch.object(f27, 'owned_sql', side_effect=[SystemExit('revoke')] if phase == 'revoke' else ['', SystemExit('grant')]):
                with self.assertRaises(SystemExit):
                    with f27.denied_privilege('favorite'):
                        if phase == 'revoke': self.fail('body after failed revoke')
                strict.assert_called_once()

    def test_given_unmeasured_counts_or_wrong_exact_class_set__when_requesting_results__then_stop_before_identity_or_oracle_io(self):
        bad_reports = ({f27.TASK: {f27.PG_CLASS: None, f27.MVC_CLASS: None}},
                       {f27.TASK: {f27.PG_CLASS: True, f27.MVC_CLASS: 2}},
                       {f27.TASK: {'foreign': 2}},
                       {f27.TASK: {f27.PG_CLASS: 2, f27.MVC_CLASS: 2}, 'foreign-task': {f27.PG_CLASS: 1}})
        for reports in bad_reports:
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'REPORTS', reports), \
                    patch.object(f27, 'require_owner') as owner, patch.object(f27, 'require_oracle_receipt') as receipt:
                with self.assertRaisesRegex(SystemExit, 'unmeasured'): f27.verify_results()
                owner.assert_not_called(); receipt.assert_not_called()
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'REPORTS', MEASURED_XML), \
                patch.object(f27, 'COUNTERS', ()), patch.object(f27, 'EXPECTED_JAR_COUNTS', None):
            with self.assertRaisesRegex(SystemExit, 'journey counts remain unmeasured'): f27.require_measurements('jar')

    def test_given_exact_source_shared_and_controlled_output_receipt__when_validating_xml__then_only_the_two_exact_utc_classes_pass(self):
        sources = pinned_sources()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / 'backend-app'; xml_inputs(root); oracle_evidence(root, sources)
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'ROOT', root), patch.object(f27, 'EVIDENCE', root / 'build/ci'), \
                    patch.object(f27, 'REPORTS', MEASURED_XML), patch.object(f27, 'require_owner', return_value=OWNER):
                f27.verify_results()
            value = json.loads((root / 'build/ci/f27-results.json').read_text())
            self.assertEqual({f27.TASK}, set(value)); self.assertEqual({f27.PG_CLASS, f27.MVC_CLASS}, set(value[f27.TASK])); self.assertEqual(2, value[f27.TASK][f27.PG_CLASS]['tests'])

    def test_given_tampered_source_shared_adapted_generated_or_redirected_receipt__when_checking_oracle_identity__then_refuse(self):
        sources = pinned_sources()
        for phase in ('source', 'duplicate', 'shared', 'adapted', 'generated', 'redirected'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve() / 'backend-app'; receipt = oracle_evidence(root, sources)
                if phase == 'source': receipt.write_text(receipt.read_text().replace(oracle.EXPECTED[f27.STREAM_SOURCE], '0' * 64, 1))
                elif phase == 'duplicate': receipt.write_text(receipt.read_text() + next(line for line in receipt.read_text().splitlines() if '  source ' in line) + '\n')
                elif phase == 'shared': (root.parent / next(iter(oracle.SHARED.values()))).write_bytes(b'changed')
                elif phase == 'adapted': (root.parent / oracle.F18_ADAPTED_PATH).write_bytes(b'changed')
                elif phase == 'generated': (root.parent / oracle.OUTPUT_ROOT / oracle.OUTPUTS[0]).write_bytes(b'changed')
                else:
                    value = receipt.read_bytes(); receipt.unlink(); alias = receipt.with_name('alias'); alias.write_bytes(value); receipt.symlink_to(alias)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'ROOT', root):
                    with self.assertRaises(SystemExit): f27.require_oracle_receipt()

    def test_given_wrong_xml_shape_fqn_count_or_hidden_unsuccessful_child__when_validating__then_no_success_receipt(self):
        defects = ('extra', 'missing', 'malformed', 'tag', 'suite', 'count', 'children', 'nested-case', 'child-fqn',
                   'failure', 'error', 'skipped', 'disabled', 'attribute-failure')
        for defect in defects:
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve() / 'backend-app'; xml_inputs(root)
                task = f27.TASK
                parent = root / 'boot/build/test-results' / task; target = parent / ('TEST-' + f27.PG_CLASS + '.xml')
                tree = ET.parse(target); suite = tree.getroot()
                if defect == 'extra': (parent / 'TEST-extra.xml').write_bytes(target.read_bytes())
                elif defect == 'missing': target.unlink()
                elif defect == 'malformed': target.write_text('<invalid')
                else:
                    if defect == 'tag': suite.tag = 'testsuites'
                    elif defect == 'suite': suite.set('name', 'foreign')
                    elif defect == 'count': suite.set('tests', '0')
                    elif defect == 'children': suite.remove(suite[0])
                    elif defect == 'nested-case': ET.SubElement(ET.SubElement(suite, 'nested'), 'testcase', classname=f27.PG_CLASS)
                    elif defect == 'child-fqn': suite[0].set('classname', 'foreign')
                    elif defect == 'disabled': suite.set('disabled', '1')
                    elif defect == 'attribute-failure': suite.set('failures', '1')
                    else: ET.SubElement(suite[0], defect)
                    tree.write(target)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f27, 'ROOT', root), patch.object(f27, 'EVIDENCE', root / 'build/ci'), \
                        patch.object(f27, 'REPORTS', MEASURED_XML), \
                        patch.object(f27, 'require_owner', return_value=OWNER), patch.object(f27, 'require_oracle_receipt'):
                    with self.assertRaises(SystemExit): f27.verify_results()
                self.assertFalse((root / 'build/ci/f27-results.json').exists())


if __name__ == '__main__':
    print('F27 fixture/XML guards: mocked resources; immutable inputs use read-only Git objects. No actual PG/JAR/SSE evidence.', flush=True)
    unittest.main()
