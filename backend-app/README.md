# PawBridge 업무 통합 빌드 기반

이 디렉터리는 기존 7개 서비스와 독립적인 Java 21 Gradle 멀티프로젝트다. 첫 단계에서는 계층 의존성 검증과 외부 서비스 없이 기동·종료하는 Spring Boot 산출물만 제공한다. 업무 API·DB 연결·운영 배포는 포함하지 않는다.

## 모듈과 의존성

각 모듈은 자신의 `build.gradle`을 갖는다.

| 모듈 | 책임 | 허용되는 직접 프로젝트 의존성 |
|---|---|---|
| boot | 실행·구현체 조립 | ui, application, infrastructure, domain |
| ui | 향후 REST 요청/응답 변환 | application, domain |
| application | 향후 업무 유스케이스와 공개 호출 | domain |
| domain | 향후 순수 업무 모델·규칙·필요한 계약 | 없음 |
| infrastructure | 향후 영속성·외부 기술 구현 | domain |

Spring Boot 플러그인은 boot에만 적용하고 나머지는 일반 Java 라이브러리다. 기존 서비스의 build·sourceSets·Application·migration·스케줄러를 가져오지 않는다. 업무별 패키지와 실제 공개 API 경계는 첫 업무 편입에서 추가한다. 현재 검사는 계층 경계이며 DDD 업무 캡슐화 완료를 뜻하지 않는다.

## 현재 실행 동작

기본 설정은 비웹이다. `PawBridgeApplication`은 context를 기동하고 정상적으로 닫은 뒤 종료한다. HTTP 포트·DB·Redis·Kafka·R2·OAuth·예약 작업이 없으며 기존 서비스나 `.env`를 읽는 설정도 없다. 이후 첫 업무 API를 추가할 때 실행 방식도 해당 변경과 함께 검증한다.

## 검증 실행 위치

현재 운영 Mac에서는 Gradle·Java 앱·컨테이너를 실행하지 않는다. 검증은 `Backend app check`의 GitHub-hosted runner에서만 수행한다. 새 경로 또는 해당 workflow가 바뀐 PR과 dev push에서만 읽기 권한의 검사 작업을 실행하며 이미지·레지스트리 게시, 운영 비밀값, infra PR, 배포 연결은 없다.

다음 명령은 이 CI의 작업 디렉터리에서 사용한다.

```sh
./gradlew --no-daemon --max-workers=1 projects check :boot:bootJar
timeout 30s java -jar boot/build/libs/pawbridge-backend-app.jar
```

CI는 JDK vendor/patch·CPU 아키텍처·Gradle/runner 버전을 기록하고 JAR·시험 결과를 보존한다. CI 통과는 Mac ARM64 실제 기동이나 기존 서비스 통합·메모리 절감의 증거가 아니다.

## 보호하는 계약

- 정확히 5개 계층 프로젝트와 허용된 production 의존 방향.
- `api`, `implementation`, `compileOnly`, `runtimeOnly`, annotation processor와 전이 경로를 통한 금지 프로젝트 접근 방지.
- domain production classpath의 외부 의존 누수 방지. 테스트용 JUnit은 production과 구분한다.
- production source root, JavaCompile의 실제 소스·`options.sourcepath`, processResources의 실제 입력 파일이 해당 모듈 내부에 있는지 검사.
- `files(...)`로 참조하는 경로의 소유 모듈을 프로젝트 디렉터리와 실제 `buildDirectory`·main output·archive 산출물에서 판정. 모듈별로 분리한 중앙 출력 디렉터리는 허용하고, 둘 이상의 모듈에 겹치는 출력 소유권은 거부한다.
- 실제 Gradle 모델을 검사하는 guard와 허용·금지 fixture의 TestKit 검증.
- 기본 context의 비웹 기동·종료, datasource·예약 작업 부재.

검사는 Gradle production 모델에 노출된 의존 관계와 입력·출력 경로를 대상으로 한다. 임의 Gradle 스크립트 실행을 격리하는 보안 샌드박스는 제공하지 않는다.

`boot:test`는 Spring context를 검증하고, 별도 `boot:architectureTest`는 `boot/src/architectureTest/java`의 Gradle 경계 fixture를 실행한다. `check`는 둘 다 실행한다. `architectureTest` sourceSet은 일반 테스트·애플리케이션의 의존성을 상속하지 않으며 JUnit과 TestKit만 사용한다. Gradle API가 제공하는 로깅 구현이 Spring의 Logback과 충돌하지 않도록 테스트 실행 classpath를 분리한다. 계층 프로젝트 수는 그대로 5개다.

TestKit은 같은 CI의 Gradle 설치와 작은 로컬 fixture를 사용한다. Tooling API의 전용 daemon은 192MiB부터 제한해 순차 재사용하며, 바깥 빌드의 `--no-daemon`과 구분한다. 모든 `Test` 태스크는 256MiB·동시 fork 1개로 제한한다. 실제 검사 범위와 테스트 사례는 `gradle/architecture.gradle` 및 `boot`의 시험 소스를 따른다. 기능별 데이터/트랜잭션·권한 검증은 향후 편입 단위에서 수행한다.

## 빌드 기준과 무결성

Java 21, Gradle 8.14.3, Spring Boot 3.4.11을 출발점으로 사용한다. 기존 앱의 Java 17 빌드와 실행 설정은 변경하지 않는다. Wrapper JAR는 기준 소스에서 복사하고 Gradle 공식 SHA-256과 일치함을 확인했으며 배포 ZIP checksum도 고정한다.

- [Gradle 배포 ZIP checksum](https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256)
- [Gradle Wrapper checksum](https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256)

전체 전환 결정·실행 기록은 Obsidian `Projects/pawbridge`의 기존 모듈형 모놀리스 전환 노트가 관리한다.
