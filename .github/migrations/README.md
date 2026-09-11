# API와 Flyway 이미지 게시

서비스 CI는 같은 checkout에서 API JAR와 migrationDistribution을 만들고 같은 서비스 저장소에 게시한다.

- API 태그: `sha-<backend-commit>`
- Migration 태그: `migration-sha-<backend-commit>`
- 실행 대상: linux/amd64, Java 17
- Migration build context: 해당 서비스의 `build/migration`만 사용

두 이미지의 게시와 digest 조회가 모두 성공해야 인프라 PR을 만든다. 인프라 values에는 API digest, migration digest, sourceRevision을 함께 기록한다. 기존 enabled, existingSchemaVerified, recoveryReference 값은 유지하며 처음 등록할 때는 enabled를 false로 둔다.

## 활성화 전 확인

1. CI 결과와 두 이미지의 소스 리비전 및 SQL 패키지를 확인한다. values의 문자열 일치만으로 출처 검증을 대신하지 않는다.
2. 대상 스키마의 baseline, 복원 근거, 전용 Secret을 확인한다.
3. 승인된 인프라 변경으로 Hibernate validate와 schemaMigration.enabled를 함께 적용한다.
4. Argo 전체 sync에서 PreSync 성공과 API Ready를 확인한다. 선택적 sync는 hook을 건너뛰므로 사용하지 않는다.

## 게시 중 실패

두 이미지의 push는 하나의 트랜잭션이 아니다. 첫 이미지 게시 후 두 번째 게시가 실패하면 부분 게시 상태가 남을 수 있다. 이 경우 인프라 PR은 생성하지 않는다.

게시된 SHA 태그를 수동으로 덮어쓰지 않는다. 레지스트리의 두 digest와 해당 CI 실행을 대조하고, 필요한 수정은 새 commit으로 게시한다. Store의 기존 SHA 태그 거절 동작은 유지한다.

이 문서는 CI 실행 계약이다. 로컬 검증 통과는 원격 게시나 운영 활성화 완료를 뜻하지 않는다.
