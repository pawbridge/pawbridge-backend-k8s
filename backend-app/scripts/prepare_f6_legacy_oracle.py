#!/usr/bin/env python3
"""Extract the actual pinned legacy list repository and its compilation-only DTOs."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_legacy_oracle as f2

SOURCE = 'a57ad47970a6df3fdad1d29d5c269f1f4ba8fff7'
PREFIX = 'community-service/src/main/java/'
PACKAGE = 'com/pawbridge/communityservice/'
EXPECTED = {
    PACKAGE + 'domain/repository/PostRepository.java': 'e02a522f870cd63ccd5e2096378ab18eaaef143218bb90c284bc5c5ecf238d05',
    PACKAGE + 'dto/response/DailyPostStats.java': '7cad38a3de8602cee93e1f1c70b9c55065cb5ecf564af5693fc3a4a9f30af513',
    PACKAGE + 'dto/response/BoardTypeStats.java': '0ac4e1a47ab1512605523de8b349d5066fb2977fa98559fb688462342fd796dd',
}
SHARED = ('domain/entity/Post.java', 'domain/entity/BoardType.java',
          'domain/entity/StringListConverter.java', 'dto/response/PostResponse.java')


def main():
    repository, output, evidence = [pathlib.Path(v).resolve() for v in sys.argv[1:]]
    if (output != repository / 'backend-app/boot/build/generated/sources/f6LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f6-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F6 oracle output')
    entries = []
    # F6 uses the same entity/converter/response as F2, without duplicate classes.
    for name in SHARED:
        source = PREFIX + PACKAGE + name
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        generated = repository / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java' / (PACKAGE + name)
        if actual != f2.EXPECTED[source] or not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared F2 oracle differs from fixed F6 input: ' + name)
        entries.append(actual + '  ' + source)
    for relative, expected in EXPECTED.items():
        source = PREFIX + relative
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy F6 input checksum mismatch: ' + source)
        target = output / relative
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F6 oracle input')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        entries.append(actual + '  ' + source)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified actual legacy F6 repository, compilation DTOs and shared F2 mappings')


if __name__ == '__main__':
    main()
