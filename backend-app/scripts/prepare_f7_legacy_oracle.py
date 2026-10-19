#!/usr/bin/env python3
"""Hash-pin the actual PostgreSQL search service; reuse verified F2/F3 classes."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_legacy_oracle as f2
import prepare_f3_legacy_oracle as f3

SOURCE = '73f2009e63d61dd66b2df365452075d71a1757b3'
PREFIX = 'community-service/src/main/java/com/pawbridge/communityservice/'
EXPECTED = {
    PREFIX + 'service/PostgresqlSearchService.java': 'c55b80fb3b365319fb08baaa808a7cbc6dd9dae2cdba6db13828b36cb90ea4a0',
    PREFIX + 'service/SearchService.java': 'be659d6be7fac0249b4f7149df513741cc0532f587201c71c9eee37436a60699',
    PREFIX + 'exception/SearchServiceUnavailableException.java': 'a14ac00166bd0dd4c6ac6572e2fa0fa2d0a8ec48f5fed71fe8f54e247fd32571',
    PREFIX + 'exception/common/ApplicationException.java': 'adf8468fe9963a747e72d3edcfd92f19dd8d0b5350555c32cfea6d34cca8fe97',
    PREFIX + 'exception/common/ErrorCode.java': '1202c261eb35c0ea914a2425b7a647e6b60c9bb205bf31be19c57d356dd18606',
}


def main():
    repository, output, evidence = [pathlib.Path(v).resolve() for v in sys.argv[1:]]
    if (output != repository / 'backend-app/boot/build/generated/sources/f7LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f7-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F7 oracle output')
    entries = []
    shared = [(source, expected, 'f2LegacyOracle') for source, expected in f2.EXPECTED.items()]
    shared += [(source, expected, 'f3LegacyOracle') for source, expected in f3.EXPECTED.items()
               if source.endswith(('/KoreanSearchTerms.java', '/InvalidSearchInputException.java'))]
    # Verify every input before creating new files. Never duplicate an existing FQCN.
    for source, expected, oracle in shared:
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        generated = repository / ('backend-app/boot/build/generated/sources/' + oracle + '/java') / source.split('/src/main/java/', 1)[1]
        if actual != expected or not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared F2/F3 oracle differs from fixed F7 input: ' + source)
        entries.append(actual + '  ' + source)
    pending = []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy F7 input checksum mismatch: ' + source)
        relative = source.split('/src/main/java/', 1)[1]
        target = output / relative
        for oracle in ('f2LegacyOracle', 'f3LegacyOracle', 'f6LegacyOracle'):
            if (repository / ('backend-app/boot/build/generated/sources/' + oracle + '/java') / relative).exists():
                raise SystemExit('Refusing duplicate F7 oracle FQCN: ' + relative)
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F7 oracle input')
        pending.append((target, content))
        entries.append(actual + '  ' + source)
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified actual legacy F7 search service, five new classes and eight shared inputs')


if __name__ == '__main__':
    main()
