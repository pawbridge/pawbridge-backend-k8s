"""Synthetic Git/API/archive contracts only; no Git, network, PG or app execution."""

import base64
import contextlib
import copy
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
import zipfile

import backend_postmerge_proof as proof


MERGE, BASE, HEAD, SOURCE, TREE = (char * 40 for char in 'abcde')
RUN, ATTEMPT = 123, 2
JAR = 'f' * 64
NOW = dt.datetime(2026, 10, 20, 3, 0, tzinfo=dt.timezone.utc)
PREFIX = '/repos/' + proof.profile.REPOSITORY
PATHS = ['backend-app/community/src/main/java/Changed.java']
WORKFLOW = b'''name: Backend app check
jobs:
  package:
    name: Build the shared Java 21 candidate
    steps:
      - name: Compile, test and package
        run: existing-full-package
      - name: Preserve shared evidence
        if: always()
        uses: existing-upload
  check:
    name: ${{ matrix.profile == 'full' && 'Java 21 build and isolated PostgreSQL/S3 HTTP contracts' || 'Pilot' }}
    steps:
      - name: Existing original and PG contracts
        run: existing-full-pg
      - name: New F30 expiry PG gate
        run: existing-f30-pg
      - name: Packaged contracts
        if: ${{ matrix.profile == 'full' }}
        run: existing-smokes
      - name: Owned cleanup
        if: always()
        run: existing-cleanup
  ci-result:
    name: Backend app CI result
    steps:
      - name: Require all selected results
        run: existing-result-gate
  proof:
    name: Verify postmerge source proof
    steps:
      - name: Select execution or reuse
        run: source-proof
'''


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def archive(members):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as zipped:
        for name, raw in members.items():
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zipped.writestr(info, raw)
    return output.getvalue()


class Inputs:
    """Explicit source facts, not a second business or XML oracle."""

    def __init__(self, directory):
        self.event = Path(directory) / 'event.json'
        self.payload = {'before': BASE, 'after': MERGE, 'ref': 'refs/heads/dev',
                        'forced': False, 'deleted': False}
        self.env = dict(GITHUB_EVENT_NAME='push', GITHUB_REPOSITORY=proof.profile.REPOSITORY,
            GITHUB_REF='refs/heads/dev', GITHUB_SHA=MERGE, GITHUB_RUN_ID='456', GITHUB_RUN_ATTEMPT='1',
            GITHUB_WORKFLOW_SHA=MERGE,
            GITHUB_WORKFLOW_REF=proof.profile.REPOSITORY + '/' + proof.WORKFLOW + '@refs/heads/dev',
            GITHUB_EVENT_PATH=str(self.event), PUSH_BEFORE_SHA=BASE,
            PULL_REQUEST_BASE_SHA=BASE, PULL_REQUEST_BASE_REF='dev', GH_TOKEN='PRIVATE_TOKEN')
        self.workflow = WORKFLOW
        self.parents = BASE + ' ' + HEAD
        self.git_calls, self.api_calls, self.archive_calls = [], [], []
        self.pr = {'id': 77, 'number': 7, 'merged': True, 'state': 'closed',
            'merge_commit_sha': MERGE, 'merged_at': '2026-10-20T02:01:00Z',
            'head': {'sha': HEAD, 'ref': 'feat/source', 'repo': {'full_name': proof.profile.REPOSITORY}},
            'base': {'sha': MERGE, 'ref': 'dev', 'repo': {'full_name': proof.profile.REPOSITORY}}}
        self.run = {'id': RUN, 'run_attempt': ATTEMPT, 'workflow_id': 4567,
            'event': 'pull_request', 'head_sha': HEAD, 'path': proof.WORKFLOW,
            'status': 'completed', 'conclusion': 'success', 'pull_requests': [],
            'created_at': '2026-10-20T01:00:00Z', 'updated_at': '2026-10-20T02:00:00Z',
            'repository': {'id': 66, 'full_name': proof.profile.REPOSITORY},
            'head_repository': {'id': 66, 'full_name': proof.profile.REPOSITORY}}
        self.runs = [self.run]
        self.detail = self.run
        self.jobs = []
        for index, (key, (name, steps)) in enumerate(proof.workflow_contract(self.workflow).items(), 1):
            self.jobs.append({'id': index, 'name': name, 'run_id': RUN, 'run_attempt': ATTEMPT,
                'status': 'completed', 'conclusion': 'success',
                'completed_at': '2026-10-20T02:00:00Z' if key == 'ci-result' else '2026-10-20T01:59:00Z',
                'steps': [{'name': step, 'status': 'completed', 'conclusion': 'success'} for step in steps]})
        self.source_identity = dict(repository=proof.profile.REPOSITORY, event_name='pull_request',
            source_sha=SOURCE, before_sha=BASE, tree_sha=TREE, workflow_sha=SOURCE,
            workflow_ref=proof.profile.REPOSITORY + '/' + proof.WORKFLOW + '@refs/pull/7/merge',
            run_id=str(RUN), run_attempt=str(ATTEMPT))
        context = dict(event_name='pull_request', repository=proof.profile.REPOSITORY,
            head_repository=proof.profile.REPOSITORY, head_ref='feat/source',
            base_sha=BASE, head_sha=HEAD, source_sha=SOURCE, tree_sha=TREE,
            run_id=str(RUN), run_attempt=str(ATTEMPT))
        self.plan = proof.profile.make_plan(context, PATHS, proof.profile.FULL, JAR)
        steps = {name: {'outcome': 'success', 'conclusion': 'success'}
                 for name in proof.profile.ALL_SMOKES + proof.profile.FULL_CONTRACTS + ('owned_cleanup',)}
        self.execution = proof.profile.audit_execution(self.plan, context, PATHS, proof.profile.FULL, JAR, steps)
        metadata = ''.join(k + '=' + self.source_identity[k] + '\n' for k in
                           ('source_sha', 'workflow_sha', 'workflow_ref')).encode()
        digest = (JAR + '  boot/build/libs/pawbridge-backend-app.jar\n').encode()
        self.members = {
            'package': dict(zip(proof.PACKAGE_MEMBERS, (metadata, digest))),
            'full': dict(zip(proof.FULL_MEMBERS, (metadata, digest, encoded(self.plan), encoded(self.execution)))),
            'proof': {'postmerge-proof.txt': encoded({'schema_version': 1, 'status': 'decided',
                'mode': 'full', 'reason': 'pull-request-full', 'identity': self.source_identity})},
        }
        self.artifacts = []
        for index, name in enumerate(('package', 'full', 'proof'), 10):
            artifact_name = {'package': 'backend-app-package-123-2', 'full': 'backend-app-check-123-2-full',
                             'proof': 'backend-app-postmerge-proof-123-2'}[name]
            self.artifacts.append({'id': index, 'name': artifact_name, 'expired': False,
                'created_at': '2026-10-20T01:59:00Z', 'expires_at': '2026-10-27T01:59:00Z',
                'size_in_bytes': None, 'digest': '',
                'workflow_run': {'id': RUN, 'head_sha': HEAD, 'repository_id': 66, 'head_repository_id': 66}})
        self.commit = {'sha': SOURCE, 'tree': {'sha': TREE}, 'parents': [{'sha': BASE}, {'sha': HEAD}]}
        self.head_commit = {'sha': HEAD, 'tree': {'sha': TREE}}
        self.file = None
        self.associated = [{'id': 77, 'number': 7}]
        self.archive_override = {}

    def git(self, *args):
        self.git_calls.append(args)
        if args == ('rev-parse', 'HEAD'): return MERGE.encode()
        if args == ('rev-parse', 'HEAD^{tree}'): return TREE.encode()
        if len(args) == 2 and args[0] == 'show' and ':' + proof.WORKFLOW in args[1]: return self.workflow
        if args == ('show', '-s', '--format=%P', MERGE): return self.parents.encode()
        if args == ('diff', '--name-only', '--no-renames', '-z', BASE, MERGE, '--'):
            return b'\0'.join(p.encode() for p in PATHS) + b'\0'
        raise AssertionError('Unexpected synthetic Git operation')

    def json(self, path):
        self.api_calls.append(path)
        if '/commits/' + MERGE + '/pulls?' in path: return self.associated
        if path == PREFIX + '/pulls/7': return self.pr
        if '/actions/workflows/backend-app-check.yml/runs?' in path:
            return {'total_count': len(self.runs), 'workflow_runs': self.runs}
        if path == PREFIX + '/actions/runs/123': return self.detail
        if '/attempts/2/jobs?' in path: return {'total_count': len(self.jobs), 'jobs': self.jobs}
        if '/actions/runs/123/artifacts?' in path:
            for item, name in zip(self.artifacts, ('package', 'full', 'proof')):
                raw = self.archive_override.get(item['id'], archive(self.members[name]))
                if item.get('digest') == '': item['digest'] = 'sha256:' + hashlib.sha256(raw).hexdigest()
                if item.get('size_in_bytes') is None: item['size_in_bytes'] = len(raw)
            return {'total_count': len(self.artifacts), 'artifacts': self.artifacts}
        if path == PREFIX + '/git/commits/' + SOURCE: return self.commit
        if path == PREFIX + '/git/commits/' + HEAD: return self.head_commit
        if path == PREFIX + '/contents/' + proof.WORKFLOW + '?ref=' + SOURCE:
            return self.file if self.file is not None else {'type': 'file', 'encoding': 'base64',
                'size': len(self.workflow), 'content': base64.b64encode(self.workflow).decode()}
        raise AssertionError('Unexpected synthetic API operation')

    def archive(self, identity):
        self.archive_calls.append(identity)
        return self.archive_override.get(identity, archive(self.members[('package', 'full', 'proof')[identity - 10]]))

    def decide(self):
        self.event.write_bytes(encoded(self.payload))
        return proof.decide(self.env, git_reader=self.git, github_reader=self, now=NOW)


class PostmergeProofTest(unittest.TestCase):
    def fixture(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return Inputs(directory.name)

    def execute(self, data, reason=None):
        result = data.decide()
        self.assertEqual('execute', result['mode'])
        self.assertNotIn('source_proof', result)
        self.assertNotIn('PRIVATE', json.dumps(result))
        if reason is not None: self.assertEqual(reason, result['reason'])
        return result

    def test_given_successful_same_tree_single_merge_and_empty_run_pr_list__when_deciding__then_reuse_only_original_proof_and_jar(self):
        for mode, reason in (('full', 'pull-request-full'),
                             ('execute', 'pull-request-validation')):
            with self.subTest(mode=mode):
                data = self.fixture()
                saved = json.loads(data.members['proof']['postmerge-proof.txt'])
                saved.update(mode=mode, reason=reason)
                data.members['proof']['postmerge-proof.txt'] = encoded(saved)
                result = data.decide()
                self.assertEqual('reused-full', result['mode'])
                self.assertEqual(SOURCE, result['source_proof']['source_sha'])
                self.assertEqual(MERGE, result['identity']['source_sha'])
                self.assertEqual(JAR, result['source_proof']['jar_sha256'])
                self.assertEqual([10, 11, 12], data.archive_calls)
                self.assertFalse(any('ls-remote' in args or 'fetch' in args for args in data.git_calls))

    def test_given_the_current_workflow__when_interpreting_the_full_recipe__then_accept_every_condition(self):
        raw = (Path(__file__).resolve().parents[1] / 'workflows' / 'backend-app-check.yml').read_bytes()
        contract = proof.workflow_contract(raw)
        self.assertEqual('Java 21 build and isolated PostgreSQL/S3 HTTP contracts', contract['check'][0])
        self.assertIn('Always restore only auth column rights and verify the prior administrator fixture',
                      contract['check'][1])
        self.assertEqual({'package', 'check', 'ci-result', 'proof'}, set(contract))

    def test_given_any_pr_including_fork__when_deciding__then_execute_validation_without_api_or_token(self):
        data = self.fixture();data.env.update(GITHUB_EVENT_NAME='pull_request', GITHUB_REF='refs/pull/7/merge')
        data.env.pop('GH_TOKEN');data.payload = {'pull_request': {'head': {'repo': {'full_name': 'fork/repo'}}}}
        self.execute(data, 'pull-request-validation');self.assertEqual([], data.api_calls)

    def test_given_invalid_push_context_or_checkout__when_deciding__then_fallback_before_api(self):
        for defect in ('before', 'after', 'forced', 'branch', 'event'):
            with self.subTest(defect=defect):
                data = self.fixture()
                if defect == 'before': data.payload['before'] = HEAD
                elif defect == 'after': data.payload['after'] = HEAD
                elif defect == 'forced': data.payload['forced'] = True
                elif defect == 'branch': data.env['GITHUB_REF'] = 'refs/heads/main'
                else: data.env['GITHUB_EVENT_NAME'] = 'workflow_dispatch'
                self.execute(data);self.assertEqual([], data.api_calls)

    def test_given_nonstandard_parent_graph_or_multiple_associated_prs__when_deciding__then_fallback(self):
        for parents in (HEAD, HEAD + ' ' + BASE, BASE + ' ' + HEAD + ' ' + SOURCE):
            data = self.fixture();data.parents = parents;self.execute(data, 'not-one-two-parent-merge')
        data = self.fixture();data.associated.append({'id': 78, 'number': 8})
        self.execute(data, 'not-one-associated-pr')

    def test_given_wrong_merged_pr_head_repository_or_target__when_deciding__then_fallback(self):
        for defect in ('not-merged', 'merge-sha', 'head', 'fork', 'target'):
            with self.subTest(defect=defect):
                data = self.fixture()
                if defect == 'not-merged': data.pr['merged'] = False
                elif defect == 'merge-sha': data.pr['merge_commit_sha'] = HEAD
                elif defect == 'head': data.pr['head']['sha'] = SOURCE
                elif defect == 'fork': data.pr['head']['repo']['full_name'] = 'fork/repo'
                else: data.pr['base']['ref'] = 'main'
                self.execute(data)

    def test_given_newest_run_failed_or_cancelled__when_an_older_green_exists__then_never_fall_back_to_the_old_green(self):
        for conclusion in ('failure', 'cancelled', None):
            data = self.fixture();data.runs = [dict(data.run, id=124, conclusion=conclusion), data.run]
            self.execute(data, 'latest-source-run-not-successful');self.assertEqual([], data.archive_calls)

    def test_given_rerun_started_between_list_and_detail__when_deciding__then_fallback(self):
        data = self.fixture();data.detail = dict(data.run, run_attempt=3, status='in_progress', conclusion=None)
        self.execute(data, 'source-run-changed')
        data = self.fixture();read = data.json;lookups = []
        def rerun_after_artifacts(path):
            if path == PREFIX + '/actions/runs/123':
                lookups.append(path)
                if len(lookups) == 2: return dict(data.run, run_attempt=3, status='in_progress', conclusion=None)
            return read(path)
        with patch.object(data, 'json', side_effect=rerun_after_artifacts):
            self.execute(data, 'source-run-changed')
        self.assertEqual(2, len(lookups))

    def test_given_wrong_source_run_identity_or_present_pr_association__when_deciding__then_fallback(self):
        for key, value in (('event', 'push'), ('head_sha', MERGE), ('path', 'another.yml'),
                           ('pull_requests', [{'number': 8}]), ('pull_requests', [{'number': 7}, None])):
            data = self.fixture();data.run[key] = value;self.execute(data)

    def test_given_missing_failed_duplicate_or_skipped_source_job__when_deciding__then_fallback(self):
        for defect in ('missing', 'failed', 'duplicate', 'wrong-attempt'):
            data = self.fixture()
            if defect == 'missing': data.jobs.pop()
            elif defect == 'failed': data.jobs[0]['conclusion'] = 'failure'
            elif defect == 'duplicate': data.jobs.append(dict(data.jobs[0], id=99))
            else: data.jobs[0]['run_attempt'] = 1
            self.execute(data)

    def test_given_unchanged_twenty_seven_jar_manifest_but_missing_or_skipped_f30_pg__when_deciding__then_fallback(self):
        for defect in ('missing', 'skipped'):
            data = self.fixture();steps = data.jobs[1]['steps']
            if defect == 'missing': del steps[1]
            else: steps[1]['conclusion'] = 'skipped'
            self.execute(data, 'missing-or-skipped-validation-step')

    def test_given_unknown_step_condition_or_continue_on_error__when_interpreting_current_recipe__then_fallback(self):
        for old, new in ((b'if: always()', b'if: secrets.unknown'),
                         (b'run: existing-full-pg', b'continue-on-error: true\n        run: existing-full-pg'),
                         (b"matrix.profile == 'full' &&", b"matrix.profile != 'note-expiry' &&"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'other' && 'Other' || 'Pilot' }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'note-expiry' && 'Scoped' || matrix.profile == 'other' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile == 'note-expiry' || always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'member-management' && 'Member' || matrix.profile == 'other' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile != 'note-expiry' && always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'signup-statistics' && 'Signup' || matrix.profile == 'other' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile != 'signup-statistics' || always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'admin-member-update' && 'Administrator' || matrix.profile == 'other' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile == 'admin-member-update' || always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'visit-collection' && 'Visit' || matrix.profile == 'unknown' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile == 'visit-collection' || always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'auth-sessions' && 'Auth' || matrix.profile == 'unknown' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile == 'auth-sessions' || always() }}"),
                         (b"|| 'Pilot' }}", b"|| matrix.profile == 'admin-post-statistics' && 'Post' || matrix.profile == 'unknown' && 'Other' || 'Pilot' }}"),
                         (b"if: ${{ matrix.profile == 'full' }}", b"if: ${{ matrix.profile == 'admin-post-statistics' || always() }}")):
            data = self.fixture();data.workflow = WORKFLOW.replace(old, new)
            self.execute(data)

    def test_given_expired_future_or_out_of_order_success__when_deciding__then_fallback(self):
        for instant in ('2026-10-19T02:59:59Z', '2026-10-20T03:00:01Z', None):
            data = self.fixture();data.jobs[2]['completed_at'] = instant
            self.execute(data)
        data = self.fixture();data.pr['merged_at'] = '2026-10-20T01:00:00Z'
        self.execute(data, 'source-proof-expired-or-unordered')
        data = self.fixture();data.run['updated_at'] = '2026-10-20T03:00:01Z'
        self.execute(data, 'source-proof-expired-or-unordered')

    def test_given_receipt_from_another_source_tree_or_attempt_or_reuse_chain__when_deciding__then_fallback(self):
        for defect in ('tree', 'source-parent', 'extra-source-parent', 'attempt', 'chain',
                       'new-mode-old-reason', 'old-mode-new-reason', 'unknown-mode', 'version'):
            data = self.fixture();saved = json.loads(data.members['proof']['postmerge-proof.txt'])
            if defect == 'tree': saved['identity']['tree_sha'] = BASE
            elif defect == 'source-parent': data.commit['parents'].reverse()
            elif defect == 'extra-source-parent': data.commit['parents'].append(None)
            elif defect == 'attempt': saved['identity']['run_attempt'] = '1'
            elif defect == 'chain': saved.update(mode='reused-full', reason='identical-tree-merged-pr-full')
            elif defect == 'new-mode-old-reason': saved['mode'] = 'execute'
            elif defect == 'old-mode-new-reason': saved['reason'] = 'pull-request-validation'
            elif defect == 'unknown-mode': saved.update(mode='scoped', reason='pull-request-validation')
            else: saved['schema_version'] = True
            data.members['proof']['postmerge-proof.txt'] = encoded(saved)
            self.execute(data)

    def test_given_different_executed_workflow_bytes_or_ref__when_deciding__then_fallback(self):
        data = self.fixture();data.file = {'type': 'file', 'encoding': 'base64',
            'size': 4, 'content': base64.b64encode(b'old!').decode()}
        self.execute(data, 'source-workflow-differs')
        data = self.fixture();data.env['GITHUB_WORKFLOW_REF'] = 'fork/other.yml@refs/heads/dev'
        self.execute(data, 'workflow-ref-differs')

    def test_given_missing_expired_duplicate_or_corrupt_artifact__when_deciding__then_fallback(self):
        for defect in ('missing', 'expired', 'duplicate', 'digest', 'zip', 'run', 'head', 'repository', 'size'):
            data = self.fixture()
            if defect == 'missing': data.artifacts.pop()
            elif defect == 'expired': data.artifacts[0]['expired'] = True
            elif defect == 'duplicate': data.artifacts.append(dict(data.artifacts[0], id=90))
            elif defect == 'digest': data.artifacts[0]['digest'] = 'sha256:' + '0' * 64
            elif defect == 'zip': data.archive_override[10] = b'not a ZIP'
            elif defect == 'run': data.artifacts[0]['workflow_run']['id'] = 122
            elif defect == 'head': data.artifacts[0]['workflow_run']['head_sha'] = SOURCE
            elif defect == 'repository': data.artifacts[0]['workflow_run']['repository_id'] = 67
            else: data.artifacts[0]['size_in_bytes'] = 33
            self.execute(data)

    def test_given_mismatched_runtime_metadata_or_jar_producer__when_deciding__then_fallback(self):
        for defect in ('source', 'workflow', 'duplicate-meta', 'jar'):
            data = self.fixture();members = data.members['full']
            if defect == 'jar': members['build/ci/boot-jar.sha256'] = ('0' * 64 + '  boot/build/libs/pawbridge-backend-app.jar\n').encode()
            elif defect == 'source': members['build/ci/runtime-metadata.txt'] = members['build/ci/runtime-metadata.txt'].replace(SOURCE.encode(), HEAD.encode(), 1)
            elif defect == 'workflow': members['build/ci/runtime-metadata.txt'] = b'source_sha=' + SOURCE.encode() + b'\n'
            else: members['build/ci/runtime-metadata.txt'] += b'source_sha=' + SOURCE.encode() + b'\n'
            self.execute(data)

    def test_given_wrong_plan_or_execution_or_masked_cleanup__when_deciding__then_fallback(self):
        for defect in ('plan', 'execution', 'cleanup', 'unknown-stage'):
            data = self.fixture();plan = copy.deepcopy(data.plan);execution = copy.deepcopy(data.execution)
            if defect == 'plan': plan['identity']['base_sha'] = HEAD
            elif defect == 'execution': execution['jar_sha256'] = '0' * 64
            elif defect == 'cleanup': execution['step_results']['owned_cleanup']['outcome'] = 'failure'
            else: execution['step_results']['jar_f30'] = {'outcome': 'success', 'conclusion': 'success'}
            data.members['full']['build/ci/contract-plan.txt'] = encoded(plan)
            data.members['full']['build/ci/contract-execution.txt'] = encoded(execution)
            self.execute(data)

    def test_given_api_unavailability_or_malformed_document__when_deciding__then_execute_without_private_error_content(self):
        data = self.fixture()
        with patch.object(data, 'json', side_effect=proof.Unavailable('api-unavailable')):
            self.execute(data, 'api-unavailable')
        with self.assertRaises(proof.Unavailable): proof.document(b'{"token":1,"token":2}')
        with self.assertRaises(proof.Unavailable): proof.document(b'{"value":NaN}')

    def test_given_bounded_pagination__when_reading__then_finish_all_pages_and_reject_duplicates_or_unbounded_results(self):
        api = Mock();api.json.side_effect = [{'total_count': 101, 'jobs': [{'id': i} for i in range(1, 101)]},
                                             {'total_count': 101, 'jobs': [{'id': 101}]}]
        self.assertEqual(101, len(proof.pages(api, PREFIX + '/jobs', 'jobs')))
        self.assertTrue(api.json.call_args.args[0].endswith('page=2'))
        for batches in ([{'total_count': 301, 'jobs': []}],
                        [{'total_count': 2, 'jobs': [{'id': 1}, {'id': 1}]}],
                        [{'total_count': 2, 'jobs': [{'id': 1}]}]):
            api.json.side_effect = batches
            with self.assertRaises(proof.Unavailable): proof.pages(api, PREFIX + '/jobs', 'jobs')

    def test_given_malicious_zip_members__when_reading_only_known_evidence__then_reject_traversal_links_duplicates_and_limits(self):
        for defect in ('traversal', 'absolute', 'backslash', 'empty-segment', 'duplicate', 'symlink', 'fifo', 'oversize'):
            with self.subTest(defect=defect):
                output = io.BytesIO()
                with zipfile.ZipFile(output, 'w') as zipped:
                    zipped.writestr('wanted.txt', b'ok')
                    if defect == 'duplicate':
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore');zipped.writestr('wanted.txt', b'changed')
                    elif defect in ('symlink', 'fifo'):
                        kind = stat.S_IFLNK if defect == 'symlink' else stat.S_IFIFO
                        entry = zipfile.ZipInfo('link');entry.external_attr = (kind | 0o777) << 16
                        zipped.writestr(entry, b'/outside')
                    elif defect == 'oversize': zipped.writestr('large', b'12345')
                    else: zipped.writestr({'traversal': '../outside', 'absolute': '/outside', 'backslash': 'a\\b', 'empty-segment': 'foo//bar'}[defect], b'bad')
                with patch.object(proof, 'MAX_MEMBER', 4 if defect == 'oversize' else proof.MAX_MEMBER):
                    with self.assertRaises(proof.Unavailable): proof.zip_members(output.getvalue(), ('wanted.txt',))
        self.assertEqual({'wanted.txt': b'ok'}, proof.zip_members(archive({'wanted.txt': b'ok', 'ignored.txt': b'ignored'}), ('wanted.txt',)))

    def test_given_signed_artifact_redirect__when_downloading__then_strip_api_bearer_and_bound_time_and_bytes(self):
        reader = proof.GitHubReader('PRIVATE_TOKEN');opener = Mock();reader.opener = opener
        redirect = urllib.error.HTTPError('https://api.github.com/private', 302, 'private',
            {'Location': 'https://owned.blob.core.windows.net/private?signature=PRIVATE_SIGNATURE'}, None)
        response = Mock();response.headers = {'Content-Length': '3'};response.read.return_value = b'zip'
        response.__enter__ = Mock(return_value=response);response.__exit__ = Mock(return_value=False)
        opener.open.side_effect = [redirect, response]
        self.assertEqual(b'zip', reader.archive(10))
        first, second = [call.args[0] for call in opener.open.call_args_list]
        self.assertEqual('Bearer PRIVATE_TOKEN', first.get_header('Authorization'))
        self.assertIsNone(second.get_header('Authorization'))
        self.assertLessEqual(opener.open.call_args.kwargs['timeout'], 10)
        self.assertEqual(proof.MAX_ARCHIVE + 1, response.read.call_args.args[0])

    def test_given_untrusted_redirect_timeout_or_size__when_reading_github__then_raise_only_fixed_unavailability(self):
        for failure in (urllib.error.HTTPError('private', 302, 'PRIVATE_TOKEN', {'Location': 'https://evil.example/private'}, None),
                        urllib.error.URLError('PRIVATE_TOKEN'), TimeoutError('PRIVATE_TOKEN')):
            reader = proof.GitHubReader('PRIVATE_TOKEN');reader.opener = Mock()
            reader.opener.open.side_effect = failure
            with self.assertRaises(proof.Unavailable) as caught: reader.archive(10)
            self.assertNotIn('PRIVATE', str(caught.exception))
        reader = proof.GitHubReader('PRIVATE_TOKEN');reader.deadline = 0;reader.opener = Mock()
        with self.assertRaisesRegex(proof.Unavailable, 'api-budget-exhausted'): reader.json(PREFIX + '/pulls/7')
        reader.opener.open.assert_not_called()

    def test_given_fixed_destination__when_writing__then_preserve_exclusive_bytes_and_refuse_redirect_or_other_path(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(proof.Path, 'cwd', return_value=Path(directory).resolve()):
            target = Path(directory).resolve() / proof.OUTPUT
            proof.write_proof(proof.OUTPUT, {'mode': 'execute'})
            before = target.read_bytes()
            with self.assertRaises(ValueError): proof.write_proof(proof.OUTPUT, {'mode': 'reused-full'})
            self.assertEqual(before, target.read_bytes())
            with self.assertRaises(ValueError): proof.write_proof('other.txt', {})
            target.unlink();target.symlink_to(target.with_name('outside.txt'))
            with self.assertRaises(ValueError): proof.write_proof(proof.OUTPUT, {})

    def test_given_cli_decision_or_internal_failure__when_running__then_emit_only_mode_or_fixed_error_without_claiming_success(self):
        for mode in ('execute', 'reused-full'):
            output = io.StringIO()
            with patch.object(proof, 'destination'), patch.object(proof, 'decide', return_value={'mode': mode}), patch.object(proof, 'write_proof') as write, contextlib.redirect_stdout(output):
                self.assertEqual(0, proof.main(['--output', str(proof.OUTPUT)]))
            self.assertEqual('mode=' + mode + '\n', output.getvalue());write.assert_called_once()
        output, error = io.StringIO(), io.StringIO()
        with patch.object(proof, 'destination'), patch.object(proof, 'decide', side_effect=RuntimeError('PRIVATE_TOKEN')), patch.object(proof, 'write_proof') as write, contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            self.assertEqual(1, proof.main(['--output', str(proof.OUTPUT)]))
        self.assertEqual('', output.getvalue());self.assertNotIn('PRIVATE', error.getvalue());write.assert_not_called()
        with patch.object(proof, 'decide') as decide, contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, proof.main(['--output', 'other.txt']))
        decide.assert_not_called()


if __name__ == '__main__':
    unittest.main()
