#!/usr/bin/env python3
"""Pin actual nickname DTO/query/method bytes without bootstrapping unrelated OAuth queries."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f3_legacy_oracle as f3

SOURCE = 'c32ae0d84b69969abd35c541dbdd4e81738c4be5'
PREFIX = 'user-service/src/main/java/'
SERVICE = PREFIX + 'com/pawbridge/userservice/service/UserServiceImpl.java'
EXPECTED = {
    PREFIX + 'com/pawbridge/userservice/dto/request/UpdateNicknameRequestDto.java': 'a351a78ae712665675be00405ee722dc62f66dae2958b90a4e494d2946c679eb',
    PREFIX + 'com/pawbridge/userservice/repository/UserRepository.java': '09e803887a6386083bc50b06ebb2da5110ad8541bf7726c2440db3d843d2d915',
    PREFIX + 'com/pawbridge/userservice/dto/response/DailySignupStatsResponse.java': '6e02c189626dcd739a01684e5747a733ba2ad962ac1ca594136a7028ce463a2e',
    PREFIX + 'com/pawbridge/userservice/exception/UserNotFoundException.java': 'ddea3a2a8fb4d48cf31a719f6ee507c427df841d9d6f40778dceaa8a3bc747da',
    PREFIX + 'com/pawbridge/userservice/exception/NicknameDuplicateException.java': '9c0ce9ad9caa1a64d37c14ae17f83e9fd98e39817beebd86d9bd870839bf12ef',
    PREFIX + 'com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java': '09ab66f94a5b980549a2153db799c00e6dbc11943b76c0af2cd64f1ef0995891',
    SERVICE: '83b83fc0a6f3a02e7f683239364fcab92bec92a1a52c5f99e8d59401a3d1d4af',
}
SHARED = tuple(PREFIX + 'com/pawbridge/userservice/' + p for p in (
    'entity/User.java', 'entity/Role.java', 'exception/common/ApplicationException.java', 'exception/common/ErrorCode.java'))


def service_wrapper(content):
    source = content.decode('utf-8')
    signature = '    @Override\n    @Transactional\n    public void updateNickname(Long userId, UpdateNicknameRequestDto requestDto) {'
    if source.count(signature) != 1:
        raise SystemExit('Fixed nickname method boundary differs')
    start = source.index(signature)
    end = source.index('\n    @Override', start + len(signature))
    method = source[start:end].rstrip() + '\n'
    # The original catch remains INSIDE the real Spring transaction proxy's commit.
    wrapper = '''package com.pawbridge.userservice.service;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.exception.NicknameDuplicateException;
import com.pawbridge.userservice.persistence.PostgresqlRollbackCharsetViolation;
import com.pawbridge.userservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
public class F13LegacyNicknameService implements F13NicknameOperation {
    private static final Logger log = LoggerFactory.getLogger(F13LegacyNicknameService.class);
    private final UserRepository userRepository;
    public F13LegacyNicknameService(UserRepository userRepository) { this.userRepository = userRepository; }
'''
    wrapper += method + '''}
interface F13NicknameOperation { void updateNickname(Long userId, UpdateNicknameRequestDto requestDto); }
'''
    return wrapper.encode('utf-8'), hashlib.sha256(method.encode('utf-8')).hexdigest()


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f13-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F13 oracle output')
    entries, inputs = [], []
    for source in SHARED:
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        generated = repository / 'backend-app/boot/build/generated/sources/f3LegacyOracle/java' / source[len(PREFIX):]
        if digest != f3.EXPECTED[source] or not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared F3 source differs from fixed F13 input: ' + source)
        entries.append(digest + '  ' + source)
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        digest = hashlib.sha256(content).hexdigest()
        if digest != expected:
            raise SystemExit('Legacy F13 input checksum mismatch: ' + source)
        entries.append(digest + '  ' + source)
        relative = source[len(PREFIX):]
        if source == SERVICE:
            content, method_digest = service_wrapper(content)
            relative = 'com/pawbridge/userservice/service/F13LegacyNicknameService.java'
            entries.append(method_digest + '  verbatim-updateNickname-method')
        target = output / relative
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F13 oracle input: ' + relative)
        inputs.append((target, content))
    # Validate every input and existing destination before writing any generated source.
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified real nickname DTO/query and verbatim service method with shared F3 bytes')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F13 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
