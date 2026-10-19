#!/usr/bin/env python3
"""Pin F19 count evidence and generate only the selected test-only MVC controller."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = '33cad5567767f8730fc2c4389815ecd031f41dc0'

EXPECTED = {
    'user-service/src/main/java/com/pawbridge/userservice/service/UserServiceImpl.java': '83b83fc0a6f3a02e7f683239364fcab92bec92a1a52c5f99e8d59401a3d1d4af',
    'user-service/src/main/java/com/pawbridge/userservice/controller/AdminController.java': 'c2345405aad66ca4ef766538cb2bb7c81ebc0a7b112b23085e09c638172c0ad0',
    'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': '09e803887a6386083bc50b06ebb2da5110ad8541bf7726c2440db3d843d2d915',
    'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'f2774b98a504ee58b166f251de938e0e6dd95b9e9f5e22e9474bf63f649de31e',
    'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': '14eb5ed4e263777a06d6e127f650a78a03932c9d4d5020ae8f6939f756679c05',
    'user-service/src/main/java/com/pawbridge/userservice/util/ResponseDTO.java': '107b22c2ac80290b8b61165fb2f8c35d7bf4a2c9b700e0a7f1a60b0501707fd3',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java': '572380180b5eeae7ff1f5cd81819ae0a2eed1b0f99beb279d333b56d0ea3f97b',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'cc92224c6cec111f3f008ff14da267154dc587987ce5f86be169e7ce9a1ea727',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'd2fcd341dd3bf2f0e65a0a4a2d2ebf3885c0e0354926da46d83b784e22fa9e4b',
    'user-service/src/main/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java': '09ab66f94a5b980549a2153db799c00e6dbc11943b76c0af2cd64f1ef0995891',
    'user-service/src/migration/resources/db/postgresql/V1__user_schema.sql': '5b6c50062231952e625e56a7577c41b03d7d5a10041380d4b2ada960ef7449a1',
    'api-gateway/src/main/java/com/pawbridge/apigateway/filter/JwtAuthorizationGatewayFilterFactory.java': 'a71227d58f17a8a71bea14bc831f4b8e75f7994d29c2dce51e4ba58cbd1c0c79',
    'api-gateway/src/main/resources/application.yml': 'f07de2bdc7cb7588466be1b2de6c7d1ec644ccd2a15af7d7fa75bccfed5537c4',
}

SHARED = {
    'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/User.java',
    'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/Role.java',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ApplicationException.java',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ErrorCode.java',
    'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/repository/UserRepository.java',
    'user-service/src/main/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java',
    'user-service/src/main/java/com/pawbridge/userservice/util/ResponseDTO.java': 'backend-app/boot/build/generated/sources/f14LegacyOracle/java/com/pawbridge/userservice/util/ResponseDTO.java',
    'user-service/src/main/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java': 'backend-app/boot/build/generated/sources/f14LegacyOracle/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java',
}

SERVICE = 'user-service/src/main/java/com/pawbridge/userservice/service/UserServiceImpl.java'
CONTROLLER = 'user-service/src/main/java/com/pawbridge/userservice/controller/AdminController.java'
GLOBAL = 'user-service/src/main/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java'
GATEWAY = 'api-gateway/src/main/java/com/pawbridge/apigateway/filter/JwtAuthorizationGatewayFilterFactory.java'
ROUTES = 'api-gateway/src/main/resources/application.yml'

SERVICE_ANNOTATED = {
    'label': 'source-only annotated UserServiceImpl.getTotalUserCount',
    'signature': '    public Long getTotalUserCount() {',
    'start_anchor': '    @Override\n    @Transactional(readOnly = true)\n    public Long getTotalUserCount() {',
    'end_delimiter': '\n    }\n',
    'sha256': '75c68fce6b8b60cbc5dafe26031a9f7baace3ae49105a89afc4e87b4c8116ff2',
}
SERVICE_BODY = {
    'label': 'source-only UserServiceImpl.getTotalUserCount signature and body',
    'signature': '    public Long getTotalUserCount() {',
    'start_anchor': '    public Long getTotalUserCount() {',
    'end_delimiter': '\n    }\n',
    'sha256': '9724a6d9735efe8758a57af3d6d65e32959e61931b0537df8c38f0c54ab28ccf',
}
CONTROLLER_METHOD = {
    'label': 'verbatim annotated AdminController.getTotalUserCount',
    'signature': '    public ResponseEntity<ResponseDTO<Long>> getTotalUserCount() {',
    'start_anchor': '    @GetMapping("/users/stats/total-users")\n',
    'end_delimiter': '\n    }\n',
    'sha256': '08e1e0108e9ff5815fe46725b1990ad7b3bc3c475a96a50a75272610217239e8',
}
GLOBAL_DAO = {
    'label': 'source-only shared GlobalExceptionRestAdvice.dbException',
    'signature': '    public ResponseEntity<ResponseDTO<Void>> dbException(DataAccessException e) {',
    'start_anchor': '    @ExceptionHandler\n    public ResponseEntity<ResponseDTO<Void>> dbException(DataAccessException e) {',
    'end_delimiter': '\n    }\n',
    'sha256': '977f544cb23fd7916ec2c89b85159cc44d3ccfdad6c284860a9d8b8b11fb06bd',
}
GLOBAL_RUNTIME = {
    'label': 'source-only shared GlobalExceptionRestAdvice.serverException',
    'signature': '    public ResponseEntity<ResponseDTO<Void>> serverException(RuntimeException e) {',
    'start_anchor': '    @ExceptionHandler\n    public ResponseEntity<ResponseDTO<Void>> serverException(RuntimeException e) {',
    'end_delimiter': '\n    }\n',
    'sha256': '9c7489d88593ead103ed4cbd1f6da9312642eff022b9dc961280f91a04aafdd1',
}
GATEWAY_ADMIN_METHOD = {
    'label': 'source-only Gateway.isAdminOnlyPath',
    'signature': '    private boolean isAdminOnlyPath(String method, String path) {',
    'start_anchor': '    private boolean isAdminOnlyPath(String method, String path) {',
    'end_delimiter': '\n    }\n',
    'sha256': 'c28d9aaefb9a2a7c52db7f57dce624d3dceefd5eb117cf1592fdc2a1d56a6a37',
}
GATEWAY_ROLE_BLOCK = {
    'label': 'source-only Gateway exact ROLE_ADMIN decision block, not a whole method',
    'signature': '                if (isAdminOnlyPath(method, path) && !role.equals("ROLE_ADMIN")) {',
    'start_anchor': '                // ADMIN만 접근 가능한 경로 체크\n',
    'end_delimiter': '\n                }\n',
    'sha256': '72827c14d1ab3944ca08a9a0717c7fc31fc3acc50292db50a2f371c692c20232',
}
CONTROLLER_ANNOTATIONS = {
    'label': 'verbatim AdminController three class annotations',
    'start_anchor': '@RestController\n',
    'end_exclusive_anchor': 'public class AdminController {',
    'sha256': '90159f35b38b9dc75e3bf19dd24ef32014b40c4752925e39e97f86ec5cc593a4',
}
ADMIN_ROUTE = {
    'label': 'source-only admin-users rewrite and JwtAuthorization route',
    'start_anchor': '        - id: admin-users\n',
    'end_exclusive_anchor': '        # Admin - Post Management (community-service)\n',
    'sha256': '20ed174eb7407e5492f88e671dce3f5560b558a6252632b9a47bf15ccb5dc4f7',
}

WRAPPER_RELATIVE = 'com/pawbridge/userservice/controller/F19LegacyTotalUserCountController.java'


def checked_unit(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F19 selected unit is not unique: ' + specification['label'])
    begin = text.index(anchor)
    signature_at = text.index(signature, begin)
    end = text.index(ending, signature_at) + len(ending)
    selected = text[begin:end]
    if not selected.endswith('}\n') or hashlib.sha256(selected.encode('utf-8')).hexdigest() != specification['sha256']:
        raise SystemExit('F19 selected unit/LF checksum mismatch: ' + specification['label'])
    return selected


def checked_section(text, specification):
    begin, end = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(end) != 1:
        raise SystemExit('F19 selected section is not unique: ' + specification['label'])
    first, last = text.index(begin), text.index(end)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or hashlib.sha256(selected.encode('utf-8')).hexdigest() != specification['sha256']:
        raise SystemExit('F19 selected section/LF checksum mismatch: ' + specification['label'])
    return selected


def extract(repository, output, evidence):
    repository, output, evidence = (pathlib.Path(value).resolve() for value in (repository, output, evidence))
    if (output != repository / 'backend-app/boot/build/generated/sources/f19LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f19-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F19 oracle output')

    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F19 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  ' + source)
    for source, relative in SHARED.items():
        generated = repository / relative
        if not generated.is_file() or generated.read_bytes() != inputs[source]:
            raise SystemExit('Shared generated bytes differ from fixed F19 source: ' + source)
        entries.append(EXPECTED[source] + '  shared-byte-identity ' + relative + ' <= ' + source)

    service, controller = inputs[SERVICE].decode('utf-8'), inputs[CONTROLLER].decode('utf-8')
    global_advice, gateway = inputs[GLOBAL].decode('utf-8'), inputs[GATEWAY].decode('utf-8')
    checked_unit(service, SERVICE_ANNOTATED)
    checked_unit(service, SERVICE_BODY)
    method = checked_unit(controller, CONTROLLER_METHOD)
    checked_unit(global_advice, GLOBAL_DAO)
    checked_unit(global_advice, GLOBAL_RUNTIME)
    checked_unit(gateway, GATEWAY_ADMIN_METHOD)
    checked_unit(gateway, GATEWAY_ROLE_BLOCK)
    annotations = checked_section(controller, CONTROLLER_ANNOTATIONS)
    checked_section(inputs[ROUTES].decode('utf-8'), ADMIN_ROUTE)
    original_field = '    private final UserService userService;\n'
    if controller.count(original_field) != 1:
        raise SystemExit('F19 original controller injection field differs')

    for specification in (SERVICE_ANNOTATED, SERVICE_BODY, CONTROLLER_METHOD, GLOBAL_DAO, GLOBAL_RUNTIME,
                          GATEWAY_ADMIN_METHOD, GATEWAY_ROLE_BLOCK, CONTROLLER_ANNOTATIONS, ADMIN_ROUTE):
        entries.append(specification['sha256'] + '  selected-unit ' + specification['label'])

    wrapper = '''package com.pawbridge.userservice.controller;
import com.pawbridge.userservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
''' + annotations + '''public class F19LegacyTotalUserCountController {
    private final CountOperation userService;

''' + method + '''
    /** Test-only injection callback, not the original UserService or its transaction proxy. */
    @FunctionalInterface
    public interface CountOperation {
        Long getTotalUserCount();
    }
}
'''
    content = wrapper.encode('utf-8')
    target = output / WRAPPER_RELATIVE
    generated_sources = repository / 'backend-app/boot/build/generated/sources'
    java_roots = list(generated_sources.glob('*/java'))
    java_roots.extend(repository / ('backend-app/boot/src/' + source_set + '/java')
                      for source_set in ('main', 'test', 'compatibilityTest'))
    for java_root in java_roots:
        duplicate = java_root / WRAPPER_RELATIVE
        if duplicate != target and duplicate.exists():
            raise SystemExit('Refusing duplicate F19 controller FQN: ' + str(duplicate))

    entries.extend((
        hashlib.sha256(content).hexdigest() + '  generated ' + WRAPPER_RELATIVE,
        'wiring: only selected AdminController class renamed to F19LegacyTotalUserCountController; minimal imports',
        'wiring: original three class annotations and complete annotated GET method remain verbatim including LF',
        'wiring: original final userService field name retained; UserService type replaced with nested CountOperation',
        'wiring: CountOperation has only Long getTotalUserCount(); original RequiredArgsConstructor generates the constructor',
        'proof: UserService count body and its read-only annotation are source evidence only; no service proxy executed',
        'proof: Gateway route, admin-path method and exact ROLE_ADMIN decision are source evidence only; no Gateway or JWT flow executed',
        'proof: eight shared FQNs are reused without copying; actual F14 User global advice bytes remain unchanged',
        'scope: only the selected test-only MVC controller is generated; no whole UserService/AdminController, OAuth, HTTP, PG or JVM is started',
        'scope: inherited SimpleJpaRepository<User,Long>.count comparison and candidate reader ACL require a separate owned-PG harness',
    ))
    receipt = ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode('utf-8')
    pending = [(target, content), (evidence, receipt)]
    for destination, expected in pending:
        if destination.is_symlink() or destination.resolve() != destination:
            raise SystemExit('Refusing redirected F19 oracle output')
        if destination.exists() and (not destination.is_file() or destination.read_bytes() != expected):
            raise SystemExit('Refusing to overwrite differing F19 oracle input or evidence')
    for destination, expected in pending:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(expected)
    print('Verified thirteen inputs, eight shared byte identities and nine source units; wrote one test-only controller and evidence')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F19 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
