#!/usr/bin/env python3
"""Extract only the hash-pinned F3 issuer, JPA event and search test oracles."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = '0e0161e03ddb31981487c4d0ece280cb04c1107c'
EXPECTED = {
    'user-service/src/main/java/com/pawbridge/userservice/jwt/JwtProvider.java': '1b20a72f1c246fe66c177da93c7990eac9feeb486774af0a88c8e653a8f71015',
    'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'f2774b98a504ee58b166f251de938e0e6dd95b9e9f5e22e9474bf63f649de31e',
    'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': '14eb5ed4e263777a06d6e127f650a78a03932c9d4d5020ae8f6939f756679c05',
    'user-service/src/main/java/com/pawbridge/userservice/exception/TokenInvalidException.java': '46d37e20819403edcc5267daa14299a8370232c00470a6e074e8a082cd08ea73',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'cc92224c6cec111f3f008ff14da267154dc587987ce5f86be169e7ce9a1ea727',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'd2fcd341dd3bf2f0e65a0a4a2d2ebf3885c0e0354926da46d83b784e22fa9e4b',
    'community-service/src/main/java/com/pawbridge/communityservice/domain/entity/OutboxEvent.java': 'e5c257f61a717a677ebff58edea75dbe25b46461a6012ffbe1a1e114cbfa2792',
    'community-service/src/main/java/com/pawbridge/communityservice/search/KoreanSearchTerms.java': 'c78b4abeb3bfa37980667fd98b54483a2cfe54fd916521969cb4bbd48af39d23',
    'community-service/src/main/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java': '9d0f1779c6bd86095e7dda06295f96526736b5f37126b95331ed1a7b81624781',
}


def main():
    repository, output, evidence = [pathlib.Path(value).resolve() for value in sys.argv[1:]]
    if (output != repository / 'backend-app/boot/build/generated/sources/f3LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f3-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F3 oracle output')
    entries = []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy F3 input checksum mismatch: ' + source)
        target = output / source.split('/src/main/java/', 1)[1]
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F3 oracle input')
        target.write_bytes(content)
        entries.append(actual + '  ' + source)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified nine fixed F3 legacy test inputs from ' + SOURCE)


if __name__ == '__main__':
    main()
