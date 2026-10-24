"""Synthetic JWT issuer checks without network, database or JVM resources."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import ci_tokens as tokens

SECRET = 'A' * 64  # Same length as f3_ci's secrets.token_urlsafe(48).


def decode(part):
    return json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))


def verify(token, secret=SECRET):
    header, payload, signature = token.split('.')
    digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[decode(header)['alg']]
    expected = hmac.new(secret.encode('utf-8'), (header + '.' + payload).encode('ascii'), digest).digest()
    actual = base64.urlsafe_b64decode(signature + '=' * (-len(signature) % 4))
    return hmac.compare_digest(expected, actual), decode(header), decode(payload)


class HostedRun:
    def __init__(self):
        self.directory = tempfile.TemporaryDirectory()
        self.temp = Path(self.directory.name).resolve()
        self.path = self.temp / 'f3-tokens-7-1.json'
        self.environment = {
            'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
            'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s', 'GITHUB_RUN_ID': '7',
            'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_TEMP': str(self.temp), 'F3_CI_OWNER': '7-1',
            'F3_TEST_TOKENS_PATH': str(self.path), 'F3_TEST_JWT_SECRET': SECRET,
        }

    def __enter__(self):
        self.patcher = patch.dict(os.environ, self.environment, clear=True)
        self.patcher.start()
        return self

    def __exit__(self, *failure):
        self.patcher.stop()
        self.directory.cleanup()

    def read(self):
        return json.loads(self.path.read_text(encoding='utf-8'))


class SigningTest(unittest.TestCase):
    def test_given_key_lengths__when_choosing_the_algorithm__then_follow_jjwt_hmac_key_sizes(self):
        self.assertEqual('HS512', tokens.algorithm('x' * 64))
        self.assertEqual('HS384', tokens.algorithm('x' * 48))
        self.assertEqual('HS256', tokens.algorithm('x' * 32))
        with self.assertRaises(SystemExit):
            tokens.algorithm('x' * 31)

    def test_given_an_access_user__when_signing__then_claims_follow_the_old_issuer_order_and_lifetime(self):
        user = tokens.TokenUser(42, 'synthetic@fixture.invalid', '합성회원', 'ROLE_SHELTER', 'F3-SYNTHETIC')
        valid, header, claims = verify(tokens.access_token(SECRET, user, 1000))
        self.assertTrue(valid)
        self.assertEqual({'alg': 'HS512'}, header)
        self.assertEqual(['sub', 'userId', 'name', 'role', 'careRegNo', 'iat', 'exp'], list(claims))
        self.assertEqual(('synthetic@fixture.invalid', 42, 'ROLE_SHELTER'),
                         (claims['sub'], claims['userId'], claims['role']))
        self.assertEqual(600, claims['exp'] - claims['iat'])
        self.assertIs(type(claims['userId']), int)

    def test_given_a_different_secret__when_verifying__then_the_signature_differs(self):
        token = tokens.access_token(SECRET, tokens.TokenUser(42, 'a@fixture.invalid', 'a'), 1000)
        self.assertFalse(verify(token, 'B' * 64)[0])


class StageTest(unittest.TestCase):
    NOW = 2_000_000_000

    def claims(self, values, key):
        valid, _, claims = verify(values[key])
        self.assertTrue(valid, key)
        return claims

    def test_given_the_base_stage__when_issuing__then_write_exactly_the_f3_key_set_and_rejection_inputs(self):
        values = tokens.stage_tokens('base', SECRET, self.NOW)
        self.assertEqual(set(tokens.BASE_KEYS), set(values))
        for role in tokens.ROLES:
            claims = self.claims(values, role)
            self.assertEqual((42, role, 'F3-SYNTHETIC'), (claims['userId'], claims['role'], claims['careRegNo']))
        self.assertEqual(['iat', 'exp'], list(self.claims(values, 'refresh')))
        self.assertLess(self.claims(values, 'expired')['exp'], self.NOW)
        self.assertFalse(verify(values['forged'])[0])
        self.assertTrue(verify(values['forged'], tokens.F3_FORGED_SECRET)[0])
        self.assertNotIn('userId', self.claims(values, 'missingId'))
        self.assertEqual(0, self.claims(values, 'zeroId')['userId'])
        self.assertEqual(-1, self.claims(values, 'negativeId')['userId'])
        self.assertEqual('42', self.claims(values, 'stringId')['userId'])
        self.assertNotIn('role', self.claims(values, 'missingRole'))
        self.assertEqual(42, self.claims(values, 'numericRole')['role'])

    def test_given_profile_stages__when_issuing__then_share_the_second_principal_and_refresh_rejections(self):
        f12 = tokens.stage_tokens('f12', SECRET, self.NOW)
        self.assertEqual(set(tokens.BASE_KEYS) | {'F12_OTHER'}, set(f12))
        f13 = tokens.stage_tokens('f13', SECRET, self.NOW)
        self.assertEqual(set(tokens.BASE_KEYS) | {'F12_OTHER', 'F13_OTHER'}, set(f13))
        self.assertEqual(f13['F12_OTHER'], f13['F13_OTHER'])
        self.assertEqual(9980001, self.claims(f13, 'F13_OTHER')['userId'])
        self.assertTrue(verify(f13['forged'], tokens.F12_FORGED_SECRET)[0])

    def test_given_each_caller_stage__when_issuing__then_identities_match_the_seeded_fixture_rows(self):
        expected = {
            'f14': {'ROLE_USER': (42, 'ROLE_USER'), 'F14_OTHER': (9980001, 'ROLE_USER'), 'expired': (42, 'ROLE_USER')},
            'f17': {'F17_CALLER': (9971001, 'ROLE_USER'), 'F17_OTHER': (9971002, 'ROLE_USER'),
                    'expired': (9971001, 'ROLE_USER')},
            'f18': {'F18_CALLER': (9972001, 'ROLE_USER'), 'expired': (9972001, 'ROLE_USER')},
            'f19': {'F19_ADMIN': (9973001, 'ROLE_ADMIN'), 'F19_USER': (9973002, 'ROLE_USER')},
            'f20': {'F20_CALLER': (9974001, 'ROLE_USER')}, 'f21': {'F21_CALLER': (9975001, 'ROLE_USER')},
            'f22': {'F22_CALLER': (9976001, 'ROLE_USER')}, 'f23': {'F23_CALLER': (9977001, 'ROLE_USER')},
            'f24': {'F24_CALLER': (9978001, 'ROLE_USER')}, 'f25': {'F25_CALLER': (9979001, 'ROLE_USER')},
            'f27': {'F27_CALLER': (9981001, 'ROLE_USER'), 'F27_OTHER': (9981002, 'ROLE_USER')},
            'f28': {'F28_CALLER': (9982001, 'ROLE_USER'), 'F28_OTHER': (9982002, 'ROLE_USER')},
            'f29': {'F29_CALLER': (9983001, 'ROLE_USER'), 'F29_OTHER': (9983002, 'ROLE_USER')},
            'member-cleanup': {'F19_ADMIN': (9973001, 'ROLE_ADMIN'), 'F29_CALLER': (9983001, 'ROLE_USER'),
                               'F29_OTHER': (9983002, 'ROLE_USER')},
        }
        for stage, keys in expected.items():
            with self.subTest(stage=stage):
                values = tokens.stage_tokens(stage, SECRET, self.NOW)
                self.assertEqual(set(keys), set(values))
                for key, (identity, role) in keys.items():
                    claims = self.claims(values, key)
                    self.assertEqual((identity, role), (claims['userId'], claims['role']))
                    if key == 'expired':
                        self.assertLess(claims['exp'], self.NOW)
                    else:
                        self.assertEqual(self.NOW + 600, claims['exp'])

    def test_given_the_f26_stage__when_issuing__then_add_a_signed_caller_copy_without_expiry(self):
        values = tokens.stage_tokens('f26', SECRET, self.NOW)
        self.assertEqual({'F26_CALLER', 'F26_OTHER', 'F26_NO_EXP'}, set(values))
        caller = self.claims(values, 'F26_CALLER')
        crafted = self.claims(values, 'F26_NO_EXP')
        caller.pop('exp')
        self.assertEqual(caller, crafted)
        self.assertEqual(9980002, self.claims(values, 'F26_OTHER')['userId'])

    def test_given_an_unknown_stage__when_issuing__then_refuse(self):
        with self.assertRaises(SystemExit):
            tokens.stage_tokens('f99', SECRET, self.NOW)


class FileTest(unittest.TestCase):
    def test_given_a_non_hosted_invocation__when_issuing__then_refuse_before_touching_files(self):
        with HostedRun() as run:
            del os.environ['GITHUB_ACTIONS']
            with self.assertRaises(SystemExit) as refused:
                tokens.issue('base')
            self.assertIn('restricted to this repository on GitHub-hosted Linux', str(refused.exception))
            self.assertFalse(run.path.exists())

    def test_given_the_base_stage__when_issuing__then_replace_the_file_with_a_private_exact_map(self):
        with HostedRun() as run, patch('builtins.print'):
            run.path.write_text('{"stale":"value"}')
            os.chmod(run.path, 0o600)
            tokens.issue('base', now=StageTest.NOW)
            self.assertEqual(set(tokens.BASE_KEYS), set(run.read()))
            self.assertEqual(0o600, stat.S_IMODE(run.path.stat().st_mode))
            self.assertFalse(run.path.with_name(run.path.name + '.tmp').exists())

    def test_given_a_later_stage__when_issuing__then_merge_and_keep_unrelated_tokens(self):
        with HostedRun() as run, patch('builtins.print'):
            tokens.issue('base', now=StageTest.NOW)
            before = run.read()
            tokens.issue('f17', now=StageTest.NOW + 5)
            after = run.read()
            self.assertEqual(set(before) | {'F17_CALLER', 'F17_OTHER'}, set(after))
            for key in set(before) - {'expired'}:
                self.assertEqual(before[key], after[key])
            self.assertEqual(9971001, verify(after['expired'])[2]['userId'])

    def test_given_a_missing_or_loose_file__when_merging__then_refuse(self):
        with HostedRun() as run, patch('builtins.print'):
            with self.assertRaises(SystemExit):
                tokens.issue('f20')
            run.path.write_text('{}')
            os.chmod(run.path, 0o644)
            with self.assertRaises(SystemExit):
                tokens.issue('f20')

    def test_given_a_foreign_or_redirected_path__when_issuing__then_refuse(self):
        with HostedRun() as run, patch('builtins.print'):
            os.environ['F3_TEST_TOKENS_PATH'] = str(run.temp / 'f3-tokens-8-1.json')
            with self.assertRaises(SystemExit):
                tokens.issue('base')
            os.environ['F3_TEST_TOKENS_PATH'] = str(run.path)
            target = run.temp / 'elsewhere.json'
            target.write_text('{}')
            run.path.symlink_to(target)
            with self.assertRaises(SystemExit):
                tokens.issue('base')
            self.assertEqual('{}', target.read_text())

    def test_given_a_different_owner__when_issuing__then_refuse(self):
        with HostedRun(), patch('builtins.print'):
            os.environ['F3_CI_OWNER'] = '9-9'
            with self.assertRaises(SystemExit):
                tokens.issue('base')


if __name__ == '__main__':
    unittest.main()
