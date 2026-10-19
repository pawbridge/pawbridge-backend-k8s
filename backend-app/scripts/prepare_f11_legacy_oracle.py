#!/usr/bin/env python3
"""Extract the pinned real comment-update request and verify shared oracle bytes."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f8_legacy_oracle as f8
import prepare_f9_legacy_oracle as f9

SOURCE = '0bad12b804ea5e90ed0db2d5109566e92978ea8d'
PREFIX = 'community-service/src/main/java/'
EXPECTED = {'com/pawbridge/communityservice/dto/request/UpdateCommentRequest.java':
            '0f57106e37e24707cb0e69e46ab904d1d4f529eb00944c7bf4323021b81872bd'}


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f11LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f11-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F11 oracle output')
    entries = []
    for stage, expected_inputs in (('f8', f8.EXPECTED), ('f9', f9.EXPECTED)):
        for relative, expected in expected_inputs.items():
            source = PREFIX + relative
            content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
            digest = hashlib.sha256(content).hexdigest()
            shared = repository / ('backend-app/boot/build/generated/sources/' + stage + 'LegacyOracle/java') / relative
            if digest != expected or not shared.is_file() or shared.read_bytes() != content:
                raise SystemExit('Shared oracle differs from fixed F11 input: ' + source)
            entries.append(digest + '  ' + source)
    inputs = []
    for relative, expected in EXPECTED.items():
        source = PREFIX + relative
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        if digest != expected:
            raise SystemExit('Legacy F11 input checksum mismatch: ' + source)
        target = output / relative
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F11 oracle input')
        inputs.append((target, content)); entries.append(digest + '  ' + source)
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified real F11 request and shared F8/F9 inputs without duplicate classes')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F11 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
