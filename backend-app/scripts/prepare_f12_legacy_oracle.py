#!/usr/bin/env python3
"""Extract only the real profile DTO and validate shared F3 User/Role source bytes."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f3_legacy_oracle as f3

SOURCE = '5473c4ccd95ae974c79acc7030228d4a717af3b3'
PREFIX = 'user-service/src/main/java/'
EXPECTED = {PREFIX + 'com/pawbridge/userservice/dto/response/UserInfoResponseDto.java':
            '55e9001190998829dd1d9c87c04f70ed1af88388ac73a29d9950f0241418d341'}
SHARED = (PREFIX + 'com/pawbridge/userservice/entity/User.java',
          PREFIX + 'com/pawbridge/userservice/entity/Role.java')


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f12LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f12-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F12 oracle output')
    entries = []
    for source in SHARED:
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        generated = repository / 'backend-app/boot/build/generated/sources/f3LegacyOracle/java' / source[len(PREFIX):]
        if digest != f3.EXPECTED[source] or not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared F3 User/Role differs from fixed F12 input: ' + source)
        entries.append(digest + '  ' + source)
    inputs = []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        if digest != expected:
            raise SystemExit('Legacy F12 input checksum mismatch: ' + source)
        target = output / source[len(PREFIX):]
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F12 oracle input')
        inputs.append((target, content))
        entries.append(digest + '  ' + source)
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified real profile DTO and shared F3 User/Role without duplicate classes')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F12 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
