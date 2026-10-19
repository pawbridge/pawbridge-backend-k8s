#!/usr/bin/env python3
"""Verify F4's fixed legacy entities without generating duplicate Java classes."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = 'a57ad47970a6df3fdad1d29d5c269f1f4ba8fff7'
EXPECTED = {
    'Post.java': ('f2LegacyOracle', '54bd8177df5c5f7ee6fd99bc8f975209cb34a25785b7a212c6f00edd075e0046'),
    'BoardType.java': ('f2LegacyOracle', 'cb6e3d431c879fccb7f8d7ca4d66765e4994718e2fc9fb5810767aeb8f0ce890'),
    'StringListConverter.java': ('f2LegacyOracle', '4ad131a25dac7192812030e9d59cbd9abfbea2ffe3db1f39791ff5545638092b'),
    'OutboxEvent.java': ('f3LegacyOracle', 'e5c257f61a717a677ebff58edea75dbe25b46461a6012ffbe1a1e114cbfa2792'),
}
PACKAGE = 'com/pawbridge/communityservice/domain/entity/'


def main():
    repository, evidence = [pathlib.Path(value).resolve() for value in sys.argv[1:]]
    if evidence != repository / 'backend-app/build/ci/f4-oracle-sha256.txt':
        raise SystemExit('Refusing non-dedicated F4 oracle evidence output')
    entries = []
    for name, (oracle, expected) in EXPECTED.items():
        source = 'community-service/src/main/java/' + PACKAGE + name
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy F4 input checksum mismatch: ' + source)
        generated = repository / ('backend-app/boot/build/generated/sources/' + oracle + '/java/' + PACKAGE + name)
        if not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared F2/F3 oracle differs from the fixed F4 input: ' + name)
        entries.append(actual + '  ' + source)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified four fixed F4 legacy inputs; reused F2/F3 classes without duplication')


if __name__ == '__main__':
    main()
