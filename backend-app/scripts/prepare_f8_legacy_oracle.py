#!/usr/bin/env python3
"""Extract only the pinned actual legacy comment entity, repository and response."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = '6c76989c3da6dd3d8641ee90fdd38517ce068341'
PREFIX = 'community-service/src/main/java/'
PACKAGE = 'com/pawbridge/communityservice/'
EXPECTED = {
    PACKAGE + 'domain/entity/Comment.java': 'a114705ea5db473d87a877dbc97c1b33a83a4716b4db902f9dac170df71b155b',
    PACKAGE + 'domain/repository/CommentRepository.java': '03bd5a3bd36f7116950abf9cc157aa299b7caffc28db60fd54af2c5cbf9e52d7',
    PACKAGE + 'dto/response/CommentResponse.java': 'f45a7a145b89b15ca281373fea7f89f99ea5cebc887f5f3028e0943a266a3cf1',
}


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f8LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f8-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F8 oracle output')
    inputs = []
    for relative, expected in EXPECTED.items():
        source = PREFIX + relative
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise SystemExit('Legacy F8 input checksum mismatch: ' + source)
        target = output / relative
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F8 oracle input')
        inputs.append((target, content, actual + '  ' + source))
    # Verify every input and destination before writing any generated source.
    for target, content, _ in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(row[2] for row in inputs) + '\n')
    print('Verified actual legacy F8 Comment entity, repository and response')


def main():
    if len(sys.argv) != 4:
        raise SystemExit('F8 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])


if __name__ == '__main__':
    main()
