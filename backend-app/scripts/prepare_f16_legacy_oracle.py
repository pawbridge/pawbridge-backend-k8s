#!/usr/bin/env python3
"""Pin the actual visit GET compile closure, without registering original lifecycle beans."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f7_legacy_oracle as f7

SOURCE = '33cad5567767f8730fc2c4389815ecd031f41dc0'
PREFIX = 'community-service/src/main/java/'
ROOT = PREFIX + 'com/pawbridge/communityservice/'
EXPECTED = {ROOT + name: digest for name, digest in {
    'visits/VisitService.java': 'd07d29993e085a18026c997ea7b008e55b733805438aee09f2a2e489195bb634',
    'visits/VisitModels.java': 'b3ac14a2d075367464ce1f83f53be7270dd827f05826c199b08d245e1c991374',
    'visits/VisitCookie.java': '9465a36e1e10023dc3c3263822e81db4e819f1cc61dd8ddb3aaa0d99aec4229f',
    'visits/VisitController.java': '6b67c965f4f732ed6961cc8d532667dc25f0f5d7d14d5074ed81ce6cfe7fb377',
    'visits/VisitRepository.java': '804ffb87bd3416f8f339f6a5cb54864e54ccece6fb6dd6e8d3c6c8fbadd5bd7e',
    'visits/VisitExceptionAdvice.java': 'ea79f58424b2891e8a107d5a6542fa544864cbe990462711f58aece0897287b9',
    'util/ResponseDTO.java': 'ad38765231567373be85c0ccc8027a49346a3a462fbac59d3a7c6e75d2db6327',
    'exception/common/GlobalExceptionRestAdvice.java': '978a02329f24d5ebe36607703ad404069ce8eeb03738bcf6dbfbdd6dd23a5519',
    'persistence/PostgresqlRollbackCharsetViolation.java': '7def844fd8f0aa83e0f32bc4de37d333093a445b481e70a7289a115ed3653b15',
}.items()}
SHARED = tuple(ROOT + 'exception/common/' + name + '.java' for name in ('ApplicationException', 'ErrorCode'))


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f16LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f16-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F16 oracle output')
    entries, pending = [], []
    for source in SHARED:
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        shared = repository / 'backend-app/boot/build/generated/sources/f7LegacyOracle/java' / source[len(PREFIX):]
        if digest != f7.EXPECTED[source] or not shared.is_file() or shared.read_bytes() != content:
            raise SystemExit('Shared F7 input differs from fixed F16 source: ' + source)
        entries.append(digest + '  ' + source)
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F16 input checksum mismatch: ' + source)
        target = output / source[len(PREFIX):]
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F16 oracle input')
        pending.append((target, content))
        entries.append(expected + '  ' + source)
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified nine original visit GET sources and two shared F7 inputs, with no lifecycle registration')


# The raw F16 service/repository already contain count/period/cleanup. No new wrapper is needed.
COLLECTION_DDL = 'community-service/src/migration/resources/db/postgresql/V11__daily_visitors.sql'
COLLECTION_EXPECTED = dict(EXPECTED, **{
    ROOT + 'visits/VisitConfiguration.java': 'afe6bf0056a8f344ea9cde9dbc03f3add809ac8dd2a3c6e23cbb34c25de5f2d8',
    COLLECTION_DDL: 'd230ea0dc083bf0a3ab9fcf16ff06fe1591722d232ddc07c42de28f057daeacb',
})
COLLECTION_EXPECTED.update({source: f7.EXPECTED[source] for source in SHARED})
COLLECTION_SHARED = {source: 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/' + source[len(PREFIX):]
                     for source in EXPECTED}
COLLECTION_SHARED.update({source: 'backend-app/boot/build/generated/sources/f7LegacyOracle/java/' + source[len(PREFIX):]
                          for source in SHARED})
COLLECTION_FRAGMENTS = ('853268d9871f04f2e3ef7b3809682c521dafb5ec57d998a2900c5cc66ef7befd',
                        'fc9d435fe61764d44932a0be423cd01ff15ff7ec504e012391cd6553a39ae6a3')


def collection_fragments(raw):
    if hashlib.sha256(raw).hexdigest() != COLLECTION_EXPECTED[COLLECTION_DDL]:
        raise SystemExit('Visit collection original V11 checksum differs')
    text = raw.decode('utf-8')
    first = text.index('CREATE TABLE pawbridge_community.daily_visitor (')
    count = text.index('CREATE TABLE pawbridge_community.daily_visit_count (')
    metadata = text.index('CREATE TABLE pawbridge_community.visit_collection (')
    fragments = (text[first:count], text[metadata:])
    if tuple(hashlib.sha256(part.encode()).hexdigest() for part in fragments) != COLLECTION_FRAGMENTS:
        raise SystemExit('Visit collection original two DDL/LF fragments differ')
    return fragments


def collection_base_receipt():
    lines = [f7.EXPECTED[source] + '  ' + source for source in SHARED]
    lines += [digest + '  ' + source for source, digest in EXPECTED.items()]
    return 'source_sha=' + SOURCE + '\n' + '\n'.join(lines) + '\n'


def collection_receipt():
    lines = ['source_sha=' + SOURCE]
    lines += [digest + '  source ' + source for source, digest in COLLECTION_EXPECTED.items()]
    lines += [COLLECTION_EXPECTED[source] + '  shared-byte-identity ' + relative
              for source, relative in COLLECTION_SHARED.items()]
    lines += [digest + '  collection-ddl-fragment-' + str(i) for i, digest in enumerate(COLLECTION_FRAGMENTS)]
    lines.append(hashlib.sha256(collection_base_receipt().encode()).hexdigest() + '  base-F16-receipt')
    return '\n'.join(lines) + '\n'


def extract_collection(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f16LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f16-collection-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F16 collection evidence')
    inputs = {}
    for source, expected in COLLECTION_EXPECTED.items():
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit('Visit collection immutable original source differs')
        inputs[source] = raw
    for source, relative in COLLECTION_SHARED.items():
        target = repository / relative
        if (target.is_symlink() or target.resolve() != target or not target.is_file()
                or target.read_bytes() != inputs[source]):
            raise SystemExit('Visit collection existing F16/F7 generated bytes differ')
    collection_fragments(inputs[COLLECTION_DDL])
    base = evidence.with_name('f16-oracle-sha256.txt')
    if (base.is_symlink() or base.resolve() != base or not base.is_file()
            or base.read_text() != collection_base_receipt()):
        raise SystemExit('Visit collection original F16 receipt differs')
    receipt = collection_receipt()
    if (evidence.is_symlink() or evidence.resolve() != evidence
            or (evidence.exists() and (not evidence.is_file() or evidence.read_text() != receipt))):
        raise SystemExit('Refusing changed F16 collection receipt')
    # Only this separate evidence file is written; all existing generated source/GET evidence is preserved.
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(receipt)
    print('Verified existing raw visit implementation and V11 two-table fragments without lifecycle registration')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[4] == '--collection':
        extract_collection(*sys.argv[1:4])
        raise SystemExit(0)
    if len(sys.argv) != 4:
        raise SystemExit('F16 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
