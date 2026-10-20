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


# Explicit additional contract; the ordinary nickname extractor above is unchanged.
ADMIN_SERVICE_METHOD = '259f4dd5c19693aca207b0ee2e425baef79929d8578e634567b37debaa195394'
ADMIN_EXPECTED = dict({path: digest for path, digest in EXPECTED.items()
                       if not path.endswith(('UpdateNicknameRequestDto.java', 'PostgresqlRollbackCharsetViolation.java'))}, **{
    PREFIX + 'com/pawbridge/userservice/entity/User.java': 'f2774b98a504ee58b166f251de938e0e6dd95b9e9f5e22e9474bf63f649de31e',
    PREFIX + 'com/pawbridge/userservice/entity/Role.java': '14eb5ed4e263777a06d6e127f650a78a03932c9d4d5020ae8f6939f756679c05',
    PREFIX + 'com/pawbridge/userservice/exception/common/ApplicationException.java': 'cc92224c6cec111f3f008ff14da267154dc587987ce5f86be169e7ce9a1ea727',
    PREFIX + 'com/pawbridge/userservice/exception/common/ErrorCode.java': 'd2fcd341dd3bf2f0e65a0a4a2d2ebf3885c0e0354926da46d83b784e22fa9e4b',
    PREFIX + 'com/pawbridge/userservice/dto/request/AdminUserUpdateRequest.java': 'a421b01e21dd1fd0ee64266c3a178191e1017a6ad1bd45160a8afe40c2b30b5e',
    PREFIX + 'com/pawbridge/userservice/shelter/ShelterApplicationException.java': '8b1a879ec99f0701b14d70d2ed4b68a4d62849de3a495d06226dd5f39ce78d83',
})
ADMIN_SHARED = {
    source: 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/' + source[len(PREFIX):]
    for source in SHARED
}
ADMIN_SHARED.update({source: 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/' + source[len(PREFIX):]
                     for source in EXPECTED if source != SERVICE and source in ADMIN_EXPECTED})
ADMIN_OUTPUTS = (
    'com/pawbridge/userservice/dto/request/AdminUserUpdateRequest.java',
    'com/pawbridge/userservice/shelter/ShelterApplicationException.java',
    'com/pawbridge/userservice/service/F13LegacyAdminUserUpdateService.java',
)
ADMIN_GENERATED = dict(zip(ADMIN_OUTPUTS, (
    'a421b01e21dd1fd0ee64266c3a178191e1017a6ad1bd45160a8afe40c2b30b5e',
    '8b1a879ec99f0701b14d70d2ed4b68a4d62849de3a495d06226dd5f39ce78d83',
    '90f003ef6ff33bb747882d9b3e0bc01a9a08e1df2152b4142aa256c8be01e495',
)))


def admin_update_wrapper(content):
    source = content.decode('utf-8')
    anchor = '    @Override\n    @Transactional\n    public void updateUserByAdmin(Long userId, AdminUserUpdateRequest request) {'
    if source.count(anchor) != 1:
        raise SystemExit('Fixed administrator update method boundary differs')
    start = source.index(anchor)
    end = source.index('\n    @Override', start + len(anchor))
    method = source[start:end].rstrip() + '\n'
    if hashlib.sha256(method.encode()).hexdigest() != ADMIN_SERVICE_METHOD:
        raise SystemExit('Fixed administrator update method/LF checksum differs')
    # Only the standalone class/logger name and its one dependency are wiring adaptations.
    header = '''package com.pawbridge.userservice.service;
import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.exception.NicknameDuplicateException;
import com.pawbridge.userservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
public class F13LegacyAdminUserUpdateService implements F13AdminUpdateOperation {
    private static final Logger log = LoggerFactory.getLogger(F13LegacyAdminUserUpdateService.class);
    private final UserRepository userRepository;
    public F13LegacyAdminUserUpdateService(UserRepository userRepository) { this.userRepository = userRepository; }
'''
    return (header + method + '''}
interface F13AdminUpdateOperation { void updateUserByAdmin(Long id, AdminUserUpdateRequest request); }
''').encode('utf-8')


def admin_update_receipt(outputs):
    entries = ['source_sha=' + SOURCE]
    entries += [ADMIN_EXPECTED[p] + '  source ' + p for p in ADMIN_EXPECTED]
    entries += [ADMIN_EXPECTED[p] + '  shared-byte-identity ' + relative
                for p, relative in ADMIN_SHARED.items()]
    entries.append(ADMIN_SERVICE_METHOD + '  verbatim-updateUserByAdmin-method')
    entries += [hashlib.sha256(content).hexdigest() + '  generated ' + relative
                for relative, content in outputs.items()]
    return '\n'.join(entries) + '\n'


def extract_admin_update(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f13-admin-update-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated administrator update oracle output')
    inputs = {}
    for source, expected in ADMIN_EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Administrator update immutable input differs')
        inputs[source] = content
    for source, relative in ADMIN_SHARED.items():
        target = repository / relative
        if target.is_symlink() or target.resolve() != target or not target.is_file() or target.read_bytes() != inputs[source]:
            raise SystemExit('Administrator update shared generated bytes differ')
    outputs = {ADMIN_OUTPUTS[0]: inputs[PREFIX + ADMIN_OUTPUTS[0]],
               ADMIN_OUTPUTS[1]: inputs[PREFIX + ADMIN_OUTPUTS[1]],
               ADMIN_OUTPUTS[2]: admin_update_wrapper(inputs[SERVICE])}
    if {path: hashlib.sha256(raw).hexdigest() for path, raw in outputs.items()} != ADMIN_GENERATED:
        raise SystemExit('Administrator update generated checksum differs')
    receipt = admin_update_receipt(outputs)
    # Validate all destinations and the separate receipt before the first mkdir/write.
    for relative, content in outputs.items():
        target = output / relative
        if (target.is_symlink() or target.resolve() != target
                or (target.exists() and (not target.is_file() or target.read_bytes() != content))):
            raise SystemExit('Refusing changed administrator update generated output')
    if (evidence.is_symlink() or evidence.resolve() != evidence
            or (evidence.exists() and (not evidence.is_file() or evidence.read_text() != receipt))):
        raise SystemExit('Refusing changed administrator update oracle receipt')
    for relative, content in outputs.items():
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(receipt)
    print('Verified selected original administrator update method and existing F13 JPA dependencies')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[4] == '--admin-update':
        extract_admin_update(*sys.argv[1:4])
        raise SystemExit(0)
    if len(sys.argv) != 4:
        raise SystemExit('F13 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
