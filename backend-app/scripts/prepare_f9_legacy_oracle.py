#!/usr/bin/env python3
"""Verify shared real comment mappings and extract the actual legacy input validation DTO."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f8_legacy_oracle as f8

SOURCE = 'acbb086ba14820cbba22a9055923158f08894011'
PREFIX = 'community-service/src/main/java/'
PACKAGE = 'com/pawbridge/communityservice/'
EXPECTED = {PACKAGE + 'dto/request/CreateCommentRequest.java':
            'bf9cdf4612e6736410f58440ddd5f554b8a46da52a62705b0f2c7ca34ef4b378'}


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f9LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f9-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F9 oracle output')
    entries = []
    for relative, expected in f8.EXPECTED.items():
        source = PREFIX + relative
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        shared = repository / 'backend-app/boot/build/generated/sources/f8LegacyOracle/java' / relative
        digest = hashlib.sha256(content).hexdigest()
        if digest != expected or not shared.is_file() or shared.read_bytes() != content:
            raise SystemExit('Shared F8 oracle differs from fixed F9 input: ' + source)
        entries.append(digest + '  ' + source)
    inputs = []
    for relative, expected in EXPECTED.items():
        source = PREFIX + relative
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        if digest != expected:
            raise SystemExit('Legacy F9 input checksum mismatch: ' + source)
        target = output / relative
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F9 oracle input')
        inputs.append((target, content))
        entries.append(digest + '  ' + source)
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified actual legacy F9 request and shared F8 mappings without duplicate classes')


def main():
    if len(sys.argv) != 4:
        raise SystemExit('F9 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])


if __name__ == '__main__':
    main()
