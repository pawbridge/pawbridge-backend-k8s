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



# Auth-only opt-in. The default nickname/admin contracts and receipts above are unchanged.
AUTH_SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
AUTH_EXPECTED = {'user-service/src/main/java/com/pawbridge/userservice/service/AuthServiceImpl.java': '2ebcd9eb54cae9e027b825d53e53f80187667994d009215f7c7de4703a527f38', 'user-service/src/main/java/com/pawbridge/userservice/entity/RefreshToken.java': '5e67e5c5384ca0e6608cdbe8bcf8781ef558d2d171d9cc4f67a0bcfc0f8cbda7', 'user-service/src/main/java/com/pawbridge/userservice/repository/RefreshTokenRepository.java': 'cdd0ea12bd6481df85259cdc36e2ffb830c565f1b139fe018406d23d64a1cc81', 'user-service/src/main/java/com/pawbridge/userservice/dto/request/RefreshTokenRequestDto.java': '0d407954139830bb51591a7e47c551e20d03a6e0e51389ab0f75fe90771a4fd5', 'user-service/src/main/java/com/pawbridge/userservice/dto/response/RefreshTokenResponseDto.java': 'ca24e0822744dc12eafee5a3a978adabda3864bd2b4133716c30e82cd90f7568', 'user-service/src/main/java/com/pawbridge/userservice/exception/RefreshTokenExpiredException.java': '0c6a15923aeaf93647f7f72ebb17dd59afa413ddda5c6acd2cd5f2e60ffa58f0', 'user-service/src/main/java/com/pawbridge/userservice/exception/RefreshTokenNotFoundException.java': '8870a55d365ecfe8da33ae5f92717699eeaaf4dc5d1ef9cdbf55759938bef2db', 'user-service/src/main/java/com/pawbridge/userservice/jwt/JwtProvider.java': '1b20a72f1c246fe66c177da93c7990eac9feeb486774af0a88c8e653a8f71015', 'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': '09e803887a6386083bc50b06ebb2da5110ad8541bf7726c2440db3d843d2d915', 'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'f2774b98a504ee58b166f251de938e0e6dd95b9e9f5e22e9474bf63f649de31e', 'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': '14eb5ed4e263777a06d6e127f650a78a03932c9d4d5020ae8f6939f756679c05', 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'cc92224c6cec111f3f008ff14da267154dc587987ce5f86be169e7ce9a1ea727', 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'd2fcd341dd3bf2f0e65a0a4a2d2ebf3885c0e0354926da46d83b784e22fa9e4b', 'user-service/src/main/java/com/pawbridge/userservice/exception/TokenInvalidException.java': '46d37e20819403edcc5267daa14299a8370232c00470a6e074e8a082cd08ea73', 'user-service/src/main/java/com/pawbridge/userservice/exception/UserNotFoundException.java': 'ddea3a2a8fb4d48cf31a719f6ee507c427df841d9d6f40778dceaa8a3bc747da'}
AUTH_METHODS = {'refreshToken': 'b383484a062417539c3ca571366e86be1d9b8da4238a6f74fa03f57ef8ba8484', 'logout': '664ffb93edb40c7e07b3080ee546778f4a8c85c46e721959a85ea3397a330dbc'}
AUTH_SHARED = {'user-service/src/main/java/com/pawbridge/userservice/jwt/JwtProvider.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/jwt/JwtProvider.java', 'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/repository/UserRepository.java', 'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/User.java', 'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/Role.java', 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ApplicationException.java', 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ErrorCode.java', 'user-service/src/main/java/com/pawbridge/userservice/exception/TokenInvalidException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/TokenInvalidException.java', 'user-service/src/main/java/com/pawbridge/userservice/exception/UserNotFoundException.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/exception/UserNotFoundException.java'}
AUTH_OUTPUTS = ('com/pawbridge/userservice/entity/RefreshToken.java', 'com/pawbridge/userservice/repository/RefreshTokenRepository.java', 'com/pawbridge/userservice/dto/request/RefreshTokenRequestDto.java', 'com/pawbridge/userservice/dto/response/RefreshTokenResponseDto.java', 'com/pawbridge/userservice/exception/RefreshTokenExpiredException.java', 'com/pawbridge/userservice/exception/RefreshTokenNotFoundException.java', 'com/pawbridge/userservice/service/F13LegacyAuthSessionsService.java')

AUTH_GENERATED = {'com/pawbridge/userservice/entity/RefreshToken.java': '5e67e5c5384ca0e6608cdbe8bcf8781ef558d2d171d9cc4f67a0bcfc0f8cbda7', 'com/pawbridge/userservice/repository/RefreshTokenRepository.java': 'cdd0ea12bd6481df85259cdc36e2ffb830c565f1b139fe018406d23d64a1cc81', 'com/pawbridge/userservice/dto/request/RefreshTokenRequestDto.java': '0d407954139830bb51591a7e47c551e20d03a6e0e51389ab0f75fe90771a4fd5', 'com/pawbridge/userservice/dto/response/RefreshTokenResponseDto.java': 'ca24e0822744dc12eafee5a3a978adabda3864bd2b4133716c30e82cd90f7568', 'com/pawbridge/userservice/exception/RefreshTokenExpiredException.java': '0c6a15923aeaf93647f7f72ebb17dd59afa413ddda5c6acd2cd5f2e60ffa58f0', 'com/pawbridge/userservice/exception/RefreshTokenNotFoundException.java': '8870a55d365ecfe8da33ae5f92717699eeaaf4dc5d1ef9cdbf55759938bef2db', 'com/pawbridge/userservice/service/F13LegacyAuthSessionsService.java': '8e97b88258ea2f4c51ba06744e74b534963fc0f96bfbce77fae42975f72be2bd'}

def auth_sessions_wrapper(content):
    source = content.decode('utf-8')
    selected = []
    anchors = (('refreshToken', '    @Override\n    @Transactional\n    public RefreshTokenResponseDto refreshToken('),
               ('logout', '    @Override\n    @Transactional\n    public void logout('))
    for name, anchor in anchors:
        if source.count(anchor) != 1:
            raise SystemExit('Fixed auth method boundary differs')
        start = source.index(anchor)
        # Include the original closing brace and exactly one real LF, without normalizing a method body.
        end = source.index('\n    }\n', start + len(anchor)) + len('\n    }\n')
        method = source[start:end]
        if hashlib.sha256(method.encode()).hexdigest() != AUTH_METHODS[name]:
            raise SystemExit('Fixed auth method/LF checksum differs')
        selected.append(method)
    header = '''package com.pawbridge.userservice.service;
import com.pawbridge.userservice.dto.request.RefreshTokenRequestDto;
import com.pawbridge.userservice.dto.response.RefreshTokenResponseDto;
import com.pawbridge.userservice.entity.RefreshToken;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.RefreshTokenExpiredException;
import com.pawbridge.userservice.exception.RefreshTokenNotFoundException;
import com.pawbridge.userservice.exception.TokenInvalidException;
import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.RefreshTokenRepository;
import com.pawbridge.userservice.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;
@Transactional(readOnly = true)
public class F13LegacyAuthSessionsService implements F13AuthSessionOperations {
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    public F13LegacyAuthSessionsService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository, JwtProvider jwtProvider) {
        this.refreshTokenRepository = refreshTokenRepository; this.userRepository = userRepository; this.jwtProvider = jwtProvider;
    }
'''
    return (header + '\n'.join(selected) + '''}
interface F13AuthSessionOperations {
    RefreshTokenResponseDto refreshToken(RefreshTokenRequestDto requestDto);
    void logout(Long userId);
}
''').encode()


def auth_sessions_receipt(outputs):
    entries = ['source_sha=' + AUTH_SOURCE]
    entries += [digest + '  source ' + path for path, digest in AUTH_EXPECTED.items()]
    entries += [AUTH_EXPECTED[path] + '  shared-byte-identity ' + relative for path, relative in AUTH_SHARED.items()]
    entries += [digest + '  verbatim-' + name + '-method' for name, digest in AUTH_METHODS.items()]
    entries += [hashlib.sha256(content).hexdigest() + '  generated ' + relative for relative, content in outputs.items()]
    return '\n'.join(entries) + '\n'


def extract_auth_sessions(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(p).resolve() for p in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f13-auth-sessions-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated auth source/evidence output')
    inputs = {}
    for path, expected in AUTH_EXPECTED.items():
        content = subprocess.check_output(['git', 'show', AUTH_SOURCE + ':' + path], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Auth immutable source differs')
        inputs[path] = content
    for path, relative in AUTH_SHARED.items():
        shared = repository / relative
        if (shared.is_symlink() or shared.resolve() != shared or not shared.is_file()
                or shared.read_bytes() != inputs[path]):
            raise SystemExit('Auth shared original bytes differ')
    outputs = {relative: inputs[PREFIX + relative] for relative in AUTH_OUTPUTS[:-1]}
    outputs[AUTH_OUTPUTS[-1]] = auth_sessions_wrapper(inputs[PREFIX + 'com/pawbridge/userservice/service/AuthServiceImpl.java'])
    receipt = auth_sessions_receipt(outputs).encode()
    pending = [(output / path, data) for path, data in outputs.items()] + [(evidence, receipt)]
    for target, data in pending:
        if target.is_symlink() or target.resolve() != target or (target.exists() and target.read_bytes() != data):
            raise SystemExit('Refusing differing auth output/receipt before any write')
    for target, data in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print('Verified selected auth methods and existing shared JPA/JWT sources; unrelated auth flows excluded')


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[4] == '--auth-sessions':
        extract_auth_sessions(*sys.argv[1:4])
        raise SystemExit(0)
    if len(sys.argv) == 5 and sys.argv[4] == '--admin-update':
        extract_admin_update(*sys.argv[1:4])
        raise SystemExit(0)
    if len(sys.argv) != 4:
        raise SystemExit('F13 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
