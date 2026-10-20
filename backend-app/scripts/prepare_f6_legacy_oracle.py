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


STATS_SOURCE = 'd938003e464c1413b8c27071e3f07d85b4e25ed6'
STATS_RAW = {
    PACKAGE + 'service/AdminPostStatsService.java': 'cb43005d6d85e5009f44e4723822856811072a429b65579b4c31efc114e8664d',
    PACKAGE + 'dto/response/PostPeriodStatsResponse.java': 'a3c80e62283c7335a7715d3c41d753de09ae45e0cbe56a0a042f8e8e81843919',
}
STATS_TODAY_SOURCE = PACKAGE + 'service/PostServiceImpl.java'
STATS_TODAY_SHA = 'ecb3c3d288754d5cb73687507a62378a0d62f479371c4d1e8dc8afb2339d43e7'
STATS_TODAY_OUTPUT = PACKAGE + 'service/F6LegacyAdminPostTodayService.java'
STATS_SHARED = {
    **{PACKAGE + name: f2.EXPECTED[PREFIX + PACKAGE + name] for name in SHARED},
    **EXPECTED,
}


def stats_today_unit(content):
    """Only the original method/annotation; no handwritten replacement query or bounds."""
    text = content.decode('utf-8')
    start = text.index('    @Override\n    @Transactional(readOnly = true)\n    public Long getTodayPostCount() {\n')
    end = text.index('\n    }', start) + len('\n    }\n')
    return text[start:end].encode('utf-8')


def stats_outputs(contents):
    unit = stats_today_unit(contents[PREFIX + STATS_TODAY_SOURCE])
    # @Override belonged to the unported PostService interface; only that wiring annotation is removed.
    method = unit.decode('utf-8').replace('    @Override\n', '', 1)
    wrapper = ('package com.pawbridge.communityservice.service;\n\n'
               'import com.pawbridge.communityservice.domain.repository.PostRepository;\n'
               'import java.time.LocalDateTime;\n'
               'import org.springframework.transaction.annotation.Transactional;\n\n'
               '/** Selected original today method; actual repository JPQL and JpaTM are test-only wiring. */\n'
               'public class F6LegacyAdminPostTodayService {\n'
               '    private final PostRepository postRepository;\n'
               '    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(\n'
               '            "com.pawbridge.communityservice.service.PostServiceImpl");\n'
               '    public F6LegacyAdminPostTodayService(PostRepository postRepository) { this.postRepository = postRepository; }\n\n'
               + method + '}\n').encode('utf-8')
    return {**{path: contents[PREFIX + path] for path in STATS_RAW}, STATS_TODAY_OUTPUT: wrapper}


def stats_receipt(contents, generated):
    lines = ['source_sha=' + STATS_SOURCE]
    lines += [hashlib.sha256(content).hexdigest() + '  source ' + path
              for path, content in sorted(contents.items())]
    lines += [expected + '  shared ' + path for path, expected in sorted(STATS_SHARED.items())]
    lines += [hashlib.sha256(stats_today_unit(contents[PREFIX + STATS_TODAY_SOURCE])).hexdigest()
              + '  selected PostServiceImpl.getTodayPostCount']
    lines += [hashlib.sha256(content).hexdigest() + '  generated ' + path
              for path, content in sorted(generated.items())]
    return ('\n'.join(lines) + '\n').encode('utf-8')


def prepare_statistics(repository, output, evidence):
    repository, output, evidence = map(pathlib.Path, (repository, output, evidence))
    expected_output = repository / 'backend-app/boot/build/generated/sources/f6LegacyOracle/java'
    expected_evidence = repository / 'backend-app/build/ci/f6-admin-post-statistics-oracle-sha256.txt'
    if (output != expected_output or evidence != expected_evidence
            or repository.resolve() != repository or output.resolve() != output or evidence.resolve() != evidence):
        raise SystemExit('Refusing non-dedicated administrator statistics oracle output')
    expected = {**{PREFIX + path: sha for path, sha in STATS_SHARED.items()},
                **{PREFIX + path: sha for path, sha in STATS_RAW.items()},
                PREFIX + STATS_TODAY_SOURCE: STATS_TODAY_SHA}
    contents = {}
    # Read-only objects and all shared bytes must pass before any new output/receipt is written.
    for path, sha in expected.items():
        content = subprocess.check_output(['git', 'show', STATS_SOURCE + ':' + path], cwd=repository)
        if hashlib.sha256(content).hexdigest() != sha:
            raise SystemExit('Administrator statistics source checksum mismatch')
        contents[path] = content
    for path in STATS_SHARED:
        root = ('f2LegacyOracle' if path in {PACKAGE + name for name in SHARED} else 'f6LegacyOracle')
        target = repository / ('backend-app/boot/build/generated/sources/' + root + '/java') / path
        if target.is_symlink() or target.resolve() != target or not target.is_file() or target.read_bytes() != contents[PREFIX + path]:
            raise SystemExit('Shared administrator statistics oracle bytes differ')
    generated = stats_outputs(contents)
    receipt = stats_receipt(contents, generated)
    pending = [(output / path, content) for path, content in generated.items()] + [(evidence, receipt)]
    roots = repository / 'backend-app/boot/build/generated/sources'
    for path in generated:
        for other in roots.glob('*/java/' + path):
            if other != output / path:
                raise SystemExit('Refusing duplicate administrator statistics oracle FQN')
    for target, content in pending:
        if (target.is_symlink() or target.resolve() != target
                or target.exists() and (not target.is_file() or target.read_bytes() != content)):
            raise SystemExit('Refusing conflicting administrator statistics oracle output or receipt')
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    print('Verified opt-in administrator post statistics source; default F6 receipt is unchanged')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[4] == '--admin-statistics':
        prepare_statistics(*(pathlib.Path(value).resolve() for value in sys.argv[1:4]))
    else:
        main()
