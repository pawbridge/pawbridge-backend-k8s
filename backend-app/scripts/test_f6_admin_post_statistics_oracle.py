#!/usr/bin/env python3
"""Synthetic file/process-control guards only; no Git, JVM, database, sockets, or source SQL execution."""
import hashlib
import pathlib
import tempfile
import unittest
from unittest import mock

import prepare_f6_legacy_oracle as oracle


TODAY = b'''    @Override
    @Transactional(readOnly = true)
    public Long getTodayPostCount() {
        log.info("today");
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime startOfNextDay = startOfDay.plusDays(1);
        return postRepository.countByCreatedAtBetween(startOfDay, startOfNextDay);
    }
'''


class AdminPostStatisticsOracleGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = pathlib.Path(self.directory.name).resolve()
        self.output = self.repo / 'backend-app/boot/build/generated/sources/f6LegacyOracle/java'
        self.evidence = self.repo / 'backend-app/build/ci/f6-admin-post-statistics-oracle-sha256.txt'
        self.contents = {oracle.PREFIX + path: ('synthetic ' + path).encode()
                         for path in {**oracle.STATS_SHARED, **oracle.STATS_RAW}}
        self.contents[oracle.PREFIX + oracle.STATS_TODAY_SOURCE] = b'class Synthetic {\n' + TODAY + b'}\n'
        shared = {path: hashlib.sha256(self.contents[oracle.PREFIX + path]).hexdigest() for path in oracle.STATS_SHARED}
        raw = {path: hashlib.sha256(self.contents[oracle.PREFIX + path]).hexdigest() for path in oracle.STATS_RAW}
        for name, value in [('STATS_SHARED', shared), ('STATS_RAW', raw),
                            ('STATS_TODAY_SHA', hashlib.sha256(self.contents[oracle.PREFIX + oracle.STATS_TODAY_SOURCE]).hexdigest())]:
            patcher = mock.patch.object(oracle, name, value); patcher.start(); self.addCleanup(patcher.stop)
        for path in shared:
            directory = 'f2LegacyOracle' if path in {oracle.PACKAGE + name for name in oracle.SHARED} else 'f6LegacyOracle'
            target = self.repo / ('backend-app/boot/build/generated/sources/' + directory + '/java') / path
            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(self.contents[oracle.PREFIX + path])

    def prepare(self):
        def pinned(command, cwd):
            self.assertEqual(command[:2], ['git', 'show']); self.assertEqual(cwd, self.repo)
            self.assertTrue(command[2].startswith(oracle.STATS_SOURCE + ':'))
            return self.contents[command[2].split(':', 1)[1]]
        with mock.patch.object(oracle.subprocess, 'check_output', side_effect=pinned):
            oracle.prepare_statistics(self.repo, self.output, self.evidence)

    def assert_no_new_outputs(self):
        self.assertFalse(self.evidence.exists())
        for path in oracle.STATS_RAW:
            self.assertFalse((self.output / path).exists())

    def test_given_one_wrong_pinned_input__when_preparing__then_reject_before_any_new_output(self):
        self.contents[oracle.PREFIX + oracle.STATS_TODAY_SOURCE] += b'changed'
        with self.assertRaisesRegex(SystemExit, 'source checksum'):
            self.prepare()
        self.assert_no_new_outputs()

    def test_given_shared_bytes_changed_or_absent__when_preparing__then_reject_without_replacing_existing_f6(self):
        path = next(iter(oracle.STATS_SHARED))
        target = self.repo / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java' / path
        for altered in (b'changed', None):
            if altered is None:
                target.unlink()
            else:
                target.write_bytes(altered)
            with self.assertRaisesRegex(SystemExit, 'Shared'):
                self.prepare()
            self.assert_no_new_outputs()

    def test_given_late_output_or_receipt_conflict__when_preparing__then_no_earlier_output_is_written(self):
        wrapper = self.output / oracle.STATS_TODAY_OUTPUT
        for target in (wrapper, self.evidence):
            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(b'keep existing')
            with self.assertRaisesRegex(SystemExit, 'conflicting'):
                self.prepare()
            self.assertEqual(target.read_bytes(), b'keep existing')
            for path in oracle.STATS_RAW:
                self.assertFalse((self.output / path).exists())
            target.unlink()

    def test_given_duplicate_fqn_or_output_symlink__when_preparing__then_fail_before_writes(self):
        duplicate = self.repo / 'backend-app/boot/build/generated/sources/anotherOracle/java' / oracle.STATS_TODAY_OUTPUT
        duplicate.parent.mkdir(parents=True, exist_ok=True); duplicate.write_bytes(b'other class')
        with self.assertRaisesRegex(SystemExit, 'duplicate'):
            self.prepare()
        self.assert_no_new_outputs(); duplicate.unlink()
        target = self.output / next(iter(oracle.STATS_RAW)); target.parent.mkdir(parents=True, exist_ok=True)
        destination = self.repo / 'outside.java'; destination.write_bytes(b'keep')
        target.symlink_to(destination)
        with self.assertRaisesRegex(SystemExit, 'conflicting'):
            self.prepare()
        self.assertEqual(destination.read_bytes(), b'keep'); self.assertFalse(self.evidence.exists())

    def test_given_all_verified_inputs__when_preparing_twice__then_keep_raw_bytes_and_only_adapt_today_wiring(self):
        old_receipt = self.evidence.with_name('f6-oracle-sha256.txt'); old_receipt.parent.mkdir(parents=True, exist_ok=True)
        old_receipt.write_bytes(b'original f6 proof')
        self.prepare(); first = self.evidence.read_bytes(); self.prepare()
        self.assertEqual(self.evidence.read_bytes(), first); self.assertEqual(old_receipt.read_bytes(), b'original f6 proof')
        for path in oracle.STATS_RAW:
            self.assertEqual((self.output / path).read_bytes(), self.contents[oracle.PREFIX + path])
        self.assertEqual(oracle.stats_today_unit(self.contents[oracle.PREFIX + oracle.STATS_TODAY_SOURCE]), TODAY)
        wrapper = (self.output / oracle.STATS_TODAY_OUTPUT).read_bytes()
        self.assertIn(TODAY.replace(b'    @Override\n', b'', 1), wrapper)
        self.assertIn(b'com.pawbridge.communityservice.service.PostServiceImpl', wrapper)
        self.assertEqual(first.count(b'  source '), 10); self.assertEqual(first.count(b'  shared '), 7)
        self.assertEqual(first.count(b'  generated '), 3); self.assertEqual(first.count(b'  selected '), 1)


if __name__ == '__main__':
    unittest.main()
