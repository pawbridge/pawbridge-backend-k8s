#!/usr/bin/env python3
"""Extract only actual hash-pinned home-video read inputs, with no application bootstrap."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = 'cb0082881391b3ba885293520c2a6024a15a2034'
PREFIX = 'community-service/src/main/java/'
ROOT = PREFIX + 'com/pawbridge/communityservice/curation/'
EXPECTED = {ROOT + name + '.java': digest for name, digest in {
    'HomeVideoModels': '10d46cc67243f67f2b027b6f242eca34a5f349907c38364a9250e77e3fae85a3',
    'HomeVideoRepository': 'ecdda873788d3b795ce95d02ad40eaafd949162ec0a03fb151a4cd27e12a0301',
    'HomeVideoService': '74bb9abd97ee698cbcfb2439f47ecadb7c0cfa6440c0f4c3a139b4a4cf5c94f3',
    'YouTubeVideoClient': 'd5de6e644ad24b410fa2bfc013ca8b33a4bc758754fabac6e0d560b712f394e2',
    'YouTubeVideoUrl': '1b6995c96f4831762f145d41708285c9178cb83308cc12ec2d4158abe3c2d0ef',
}.items()}


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f15LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f15-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F15 oracle output')
    entries, inputs = [], []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F15 input checksum mismatch: ' + source)
        target = output / source[len(PREFIX):]
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F15 oracle input')
        entries.append(expected + '  ' + source)
        inputs.append((target, content))
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified five real home-video model, service, repository and collaborator sources')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F15 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
