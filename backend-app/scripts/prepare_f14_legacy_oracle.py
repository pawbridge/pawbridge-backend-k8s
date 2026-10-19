#!/usr/bin/env python3
"""Pin actual Favorite MVC/advice and derived repository inputs; never run a full service."""
import hashlib
import pathlib
import subprocess
import sys

import prepare_f3_legacy_oracle as f3
import prepare_f13_legacy_oracle as f13

SOURCE = '1458169254f71e34148711b7caf72ec75c35e5f2'
PREFIX = 'user-service/src/main/java/'
ROOT = PREFIX + 'com/pawbridge/userservice/'
EXPECTED = {ROOT + name: digest for name, digest in {
    'controller/FavoriteController.java': '7dd924d89c223c0a2366e5c6a82c65346be62225ba5b7df035cd4a969ec42d94',
    'service/FavoriteService.java': '1f85ce15990925f7eac51e3b19e4a119c7cb0683d788c68dd4e064da49ba31d2',
    'util/ResponseDTO.java': '107b22c2ac80290b8b61165fb2f8c35d7bf4a2c9b700e0a7f1a60b0501707fd3',
    'exception/common/GlobalExceptionRestAdvice.java': '572380180b5eeae7ff1f5cd81819ae0a2eed1b0f99beb279d333b56d0ea3f97b',
    'dto/response/FavoriteResponseDto.java': '8d185ccb98c6edbbd551f8e47f094e0ed06285260a91ec5d6c82260abd6772d5',
    'dto/response/FavoriteListResponseDto.java': '67168cd741ff7e43aea79fe32afa0241a16aeac4d0b818d3c99e87734100a77a',
    'dto/response/FavoriteWithAnimalDto.java': '31ae0797effb15b4fff748d365f180ac6ecffc2d95dd5ffa831dad69a672cf48',
    'dto/response/AnimalResponse.java': '2dd754815b6ff4a3f35469e16b24647e93f23d2eff9d8bde327c420319b67c74',
    'entity/Favorite.java': '1d19c20ecb537f99d35f175627531e7ce02ae98bdadbbfefdc2cf31b99efe87d',
    'repository/FavoriteRepository.java': '6a12b321c2604a3fb8822f2729f3276ad42eb7f51401949e55102ee96de85b31',
}.items()}
SHARED = {ROOT + p: ('f3', f3.EXPECTED[ROOT + p]) for p in (
    'entity/User.java', 'entity/Role.java', 'exception/common/ApplicationException.java', 'exception/common/ErrorCode.java')}
SHARED[ROOT + 'persistence/PostgresqlRollbackCharsetViolation.java'] = (
    'f13', f13.EXPECTED[ROOT + 'persistence/PostgresqlRollbackCharsetViolation.java'])
SERVICE = ROOT + 'service/FavoriteServiceImpl.java'
SERVICE_SHA = '1b4a1c125d0d9da2a8298f083c4da51f89c4a0d8e095ef8c94ed991e066eab44'
METHOD = '''    @Override
    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long animalId) {
        return favoriteRepository.existsByUserUserIdAndAnimalId(userId, animalId);
    }'''


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f14LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f14-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F14 oracle output')
    entries, inputs = [], []
    for source, (stage, expected) in SHARED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        generated = repository / ('backend-app/boot/build/generated/sources/' + stage + 'LegacyOracle/java') / source[len(PREFIX):]
        if hashlib.sha256(content).hexdigest() != expected or not generated.is_file() or generated.read_bytes() != content:
            raise SystemExit('Shared source differs from fixed F14 input: ' + source)
        entries.append(expected + '  ' + source)
    service = subprocess.check_output(['git', 'show', SOURCE + ':' + SERVICE], cwd=repository)
    if hashlib.sha256(service).hexdigest() != SERVICE_SHA or service.decode('utf-8').count(METHOD) != 1:
        raise SystemExit('Fixed read-only Favorite method differs')
    entries.extend((SERVICE_SHA + '  ' + SERVICE, hashlib.sha256(METHOD.encode()).hexdigest() + '  verified-isFavorite-method'))
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F14 input checksum mismatch: ' + source)
        target = output / source[len(PREFIX):]
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F14 oracle input')
        entries.append(expected + '  ' + source)
        inputs.append((target, content))
    for target, content in inputs:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified real Favorite MVC/advice, derived repository and read-only method inputs')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F14 oracle requires repository, source output and evidence')
    extract(*sys.argv[1:])
