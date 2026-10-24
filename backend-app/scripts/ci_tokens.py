#!/usr/bin/env python3
"""Issue the synthetic JWTs that the packaged JAR smokes read from the run-owned token file.

The deleted old-MSA compatibility tests used to write this file with the old user-service
JwtProvider. This helper reproduces the same token shape with the standard library only:

- key: the UTF-8 bytes of F3_TEST_JWT_SECRET, like JJWT ``Keys.hmacShaKeyFor``;
- algorithm: chosen from the key length like JJWT ``signWith(key)``
  (HS512 for >= 64 bytes, HS384 for >= 48 bytes, otherwise HS256);
- access claims in the JwtProvider order: sub, userId, name, role, optional careRegNo, iat, exp;
- a 600 second lifetime, like ``new JwtProvider(secret, 600_000, 600_000)``.

Each stage replaces only its own keys and keeps every other key, except the base stage, which
writes the exact twelve-key map that the F3 smoke requires.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import sys
import time

import f2_ci as f2

LIFETIME_SECONDS = 600
EXPIRED_OFFSET_SECONDS = -60
ROLES = ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER')
BASE_KEYS = frozenset(ROLES + ('refresh', 'expired', 'forged', 'missingId', 'zeroId',
                               'negativeId', 'stringId', 'missingRole', 'numericRole'))
INVALID_CLAIMS = {
    'missingId': {'role': 'ROLE_USER'},
    'zeroId': {'userId': 0, 'role': 'ROLE_USER'},
    'negativeId': {'userId': -1, 'role': 'ROLE_USER'},
    'stringId': {'userId': '42', 'role': 'ROLE_USER'},
    'missingRole': {'userId': 42},
    'numericRole': {'userId': 42, 'role': 42},
}
F3_FORGED_SECRET = 'f3-wrong-synthetic-secret-at-least-sixty-four-characters-0123456789012345'
F12_FORGED_SECRET = 'f12-wrong-synthetic-secret-at-least-sixty-four-characters-0123456789012345'
STAGES = ('base', 'f12', 'f13', 'f14', 'f17', 'f18', 'f19', 'f20', 'f21', 'f22', 'f23',
          'f24', 'f25', 'f26', 'f27', 'f28', 'f29', 'member-cleanup')


class TokenUser:
    def __init__(self, user_id, email, name, role='ROLE_USER', care_reg_no=None):
        self.user_id = user_id
        self.email = email
        self.name = name
        self.role = role
        self.care_reg_no = care_reg_no


def _encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def algorithm(secret):
    size = len(secret.encode('utf-8'))
    if size < 32:
        raise SystemExit('The synthetic JWT secret is shorter than the HMAC-SHA minimum')
    if size >= 64:
        return 'HS512'
    if size >= 48:
        return 'HS384'
    return 'HS256'


def sign(secret, claims):
    name = algorithm(secret)
    digest = {'HS256': hashlib.sha256, 'HS384': hashlib.sha384, 'HS512': hashlib.sha512}[name]
    signing_input = _encode(_json({'alg': name})) + '.' + _encode(_json(claims))
    signature = hmac.new(secret.encode('utf-8'), signing_input.encode('ascii'), digest).digest()
    return signing_input + '.' + _encode(signature)


def access_claims(user, now, lifetime=LIFETIME_SECONDS):
    claims = {'sub': user.email, 'userId': user.user_id, 'name': user.name, 'role': user.role}
    if user.care_reg_no:
        claims['careRegNo'] = user.care_reg_no
    claims['iat'] = now
    claims['exp'] = now + lifetime
    return claims


def access_token(secret, user, now, lifetime=LIFETIME_SECONDS):
    return sign(secret, access_claims(user, now, lifetime))


def _rejection_tokens(secret, now, user, forged_secret):
    """Refresh, expired, forged and malformed-claim inputs shared by the base, F12 and F13 stages."""
    tokens = {
        'refresh': sign(secret, {'iat': now, 'exp': now + LIFETIME_SECONDS}),
        'expired': access_token(secret, user, now, EXPIRED_OFFSET_SECONDS),
        'forged': access_token(forged_secret, user, now),
    }
    for name, claims in INVALID_CLAIMS.items():
        tokens[name] = sign(secret, dict(claims, iat=now, exp=now + LIFETIME_SECONDS))
    return tokens


def _base(secret, now):
    tokens = {}
    for role in ROLES:
        user = TokenUser(42, 'synthetic@fixture.invalid', '합성회원', role, 'F3-SYNTHETIC')
        tokens[role] = access_token(secret, user, now)
    rejected = TokenUser(42, 'synthetic@fixture.invalid', '합성회원')
    tokens.update(_rejection_tokens(secret, now, rejected, F3_FORGED_SECRET))
    return tokens


def _profile_stage(secret, now, email, name, other_keys):
    tokens = {}
    for role in ROLES:
        tokens[role] = access_token(secret, TokenUser(42, email, name, role), now)
    other = access_token(secret, TokenUser(9980001, email, name), now)
    for key in other_keys:
        tokens[key] = other
    tokens.update(_rejection_tokens(secret, now, TokenUser(42, email, name), F12_FORGED_SECRET))
    return tokens


def _callers(secret, now, prefix, label, identities, expired_identity=None):
    tokens = {}
    for key, user_id in identities:
        tokens[key] = access_token(secret, TokenUser(user_id, prefix + '-token@fixture.invalid', label), now)
    if expired_identity is not None:
        user = TokenUser(expired_identity, prefix + '-token@fixture.invalid', label)
        tokens['expired'] = access_token(secret, user, now, EXPIRED_OFFSET_SECONDS)
    return tokens


def _numbered(secret, now, prefix, label, identities, roles=None):
    tokens = {}
    for key, user_id in identities:
        role = (roles or {}).get(key, 'ROLE_USER')
        user = TokenUser(user_id, prefix + '-token-' + str(user_id) + '@fixture.invalid', label, role)
        tokens[key] = access_token(secret, user, now)
    return tokens


def stage_tokens(stage, secret, now):
    """Return only the keys that the given deleted compatibility test (re)issued."""
    if stage == 'base':
        return _base(secret, now)
    if stage == 'f12':
        return _profile_stage(secret, now, 'f12-token-claim@fixture.invalid', 'F12 토큰 클레임', ('F12_OTHER',))
    if stage == 'f13':
        return _profile_stage(secret, now, 'f13-token@fixture.invalid', 'F13 token claim',
                              ('F12_OTHER', 'F13_OTHER'))
    if stage == 'f14':
        return _callers(secret, now, 'f14', 'F14 token claim',
                        (('ROLE_USER', 42), ('F14_OTHER', 9980001)), expired_identity=42)
    if stage == 'f17':
        return _callers(secret, now, 'f17', 'F17 synthetic',
                        (('F17_CALLER', 9971001), ('F17_OTHER', 9971002)), expired_identity=9971001)
    if stage == 'f18':
        return _callers(secret, now, 'f18', 'F18 synthetic', (('F18_CALLER', 9972001),),
                        expired_identity=9972001)
    if stage == 'f19':
        tokens = {}
        for key, user_id, role in (('F19_ADMIN', 9973001, 'ROLE_ADMIN'), ('F19_USER', 9973002, 'ROLE_USER')):
            user = TokenUser(user_id, 'f19-token@fixture.invalid', 'F19 synthetic', role)
            tokens[key] = access_token(secret, user, now)
        return tokens
    callers = {'f20': 9974001, 'f21': 9975001, 'f22': 9976001, 'f23': 9977001, 'f24': 9978001, 'f25': 9979001}
    if stage in callers:
        number = stage[1:]
        return _callers(secret, now, stage, 'F' + number + ' synthetic',
                        (('F' + number + '_CALLER', callers[stage]),))
    pairs = {'f26': (9980001, 9980002), 'f27': (9981001, 9981002), 'f28': (9982001, 9982002),
             'f29': (9983001, 9983002)}
    if stage in pairs:
        number = stage[1:]
        caller, other = pairs[stage]
        tokens = _numbered(secret, now, stage, 'F' + number + ' synthetic',
                           (('F' + number + '_CALLER', caller), ('F' + number + '_OTHER', other)))
        if stage == 'f26':
            # Crafted boundary input: the caller claims without exp, same header and key.
            user = TokenUser(caller, 'f26-token-' + str(caller) + '@fixture.invalid', 'F26 synthetic')
            claims = access_claims(user, now)
            claims.pop('exp')
            tokens['F26_NO_EXP'] = sign(secret, claims)
        return tokens
    if stage == 'member-cleanup':
        return _numbered(secret, now, 'member-cleanup', 'Cleanup synthetic',
                         (('F19_ADMIN', 9973001), ('F29_CALLER', 9983001), ('F29_OTHER', 9983002)),
                         roles={'F19_ADMIN': 'ROLE_ADMIN'})
    raise SystemExit('Unknown synthetic token stage')


def owned_token_path():
    """The same run-owned location the F3 fixture exported and the deleted Java tests checked."""
    owner = f2.require_hosted()
    if os.environ.get('F3_CI_OWNER') != owner:
        raise SystemExit('Synthetic token owner differs from this hosted run')
    try:
        expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
        path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    except KeyError:
        raise SystemExit('Synthetic token location is not exported') from None
    if not path.is_absolute() or path != expected or path.is_symlink():
        raise SystemExit('Synthetic token file must be the run-owned path without a symlink')
    return path


def read_tokens(path):
    try:
        mode = path.lstat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('Synthetic token file must remain a regular 0600 file')
        values = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raise SystemExit('Synthetic token file is missing or malformed') from None
    if not isinstance(values, dict) or not all(isinstance(k, str) and isinstance(v, str)
                                               for k, v in values.items()):
        raise SystemExit('Synthetic token file must contain a string token map')
    return values


def write_tokens(path, tokens):
    """Write the whole map to a fresh 0600 file in the same directory, then rename it into place."""
    temporary = path.with_name(path.name + '.tmp')
    if temporary.exists() or temporary.is_symlink():
        raise SystemExit('A stale synthetic token temporary file exists')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
            json.dump(tokens, output, ensure_ascii=False, separators=(',', ':'))
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        if temporary.exists():
            temporary.unlink()
        raise


def issue(stage, now=None):
    if stage not in STAGES:
        raise SystemExit('Unknown synthetic token stage')
    path = owned_token_path()
    secret = os.environ.get('F3_TEST_JWT_SECRET', '')
    now = int(time.time()) if now is None else now
    fresh = stage_tokens(stage, secret, now)
    if stage == 'base':
        tokens = fresh
    else:
        tokens = read_tokens(path)
        tokens.update(fresh)
    for value in fresh.values():
        print('::add-mask::' + value)
    write_tokens(path, tokens)
    print('Issued ' + str(len(fresh)) + ' synthetic tokens for stage ' + stage)
    return sorted(fresh)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    issuing = commands.add_parser('issue')
    issuing.add_argument('--stage', choices=STAGES, required=True)
    args = parser.parse_args(argv)
    if args.command == 'issue':
        issue(args.stage)
    return 0


if __name__ == '__main__':
    sys.exit(main())
