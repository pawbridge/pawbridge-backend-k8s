#!/usr/bin/env python3
"""Extract only hash-pinned legacy test inputs; never compile the legacy application."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = 'e8ad7b8ec9006772ccc5f5881334df2730dbd8de'
EXPECTED = {'community-service/src/main/java/com/pawbridge/communityservice/domain/entity/Post.java': '54bd8177df5c5f7ee6fd99bc8f975209cb34a25785b7a212c6f00edd075e0046', 'community-service/src/main/java/com/pawbridge/communityservice/domain/entity/BoardType.java': 'cb6e3d431c879fccb7f8d7ca4d66765e4994718e2fc9fb5810767aeb8f0ce890', 'community-service/src/main/java/com/pawbridge/communityservice/domain/entity/StringListConverter.java': '4ad131a25dac7192812030e9d59cbd9abfbea2ffe3db1f39791ff5545638092b', 'community-service/src/main/java/com/pawbridge/communityservice/dto/response/PostResponse.java': 'b87ff09c1cc83f3877f114cfa4816e528392f7c555c19942ac222f38ca33614d', 'community-service/src/main/java/com/pawbridge/communityservice/client/UserServiceClient.java': '7dfe06774f5e44c18597cbc9f1b3b0670880807f19e70df5ce76c26ff41be216', 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java': '5f4c2e1026025aedaaed24922faf440805323757c3b2d2589cf69f80da0f849a'}


def main():
    repository, output, evidence = [pathlib.Path(v).resolve() for v in sys.argv[1:]]
    expected_output = repository / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java'
    expected_evidence = repository / 'backend-app/build/ci/oracle-sha256.txt'
    if output != expected_output or evidence != expected_evidence:
        raise SystemExit('Refusing non-dedicated oracle output')
    entries = []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy input checksum mismatch: ' + source)
        target = output / source.split('/src/main/java/', 1)[1]
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing oracle input')
        target.write_bytes(content)
        entries.append(actual + '  ' + source)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified six fixed legacy test inputs from ' + SOURCE)


if __name__ == '__main__':
    main()
