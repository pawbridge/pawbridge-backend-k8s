# PawBridge 업무 통합 빌드 기반

이 디렉터리는 기존 서비스와 독립적인 Java 21 Gradle 멀티프로젝트다. F1은 업무 모듈의 의존 경계와 외부 서비스 없이 기동·종료하는 Spring Boot 산출물을 제공한다. 실제 User·Community 기능 편입은 다음 단계에서 진행한다.

## 모듈과 의존성

| Gradle 모듈 | 책임 | 허용되는 직접 프로젝트 의존성 |
|---|---|---|
| boot | 실행 설정, 구현체 조립, 실행 JAR | user, community |
| user | 사용자 업무와 다른 업무에 제공할 계약 | 없음 |
| community | 커뮤니티 업무와 다른 업무에 제공할 계약 | user |

루트는 빌드 집계만 담당한다. Spring Boot 플러그인과 실행 JAR는 boot에만 둔다. 업무 모듈은 일반 Java 라이브러리다. 기존 서비스의 build·sourceSets·Application·migration·스케줄러는 가져오지 않는다.

업무 모듈 내부의 패키지 배치는 다음을 기준으로 한다. 아래 역할은 별도 Gradle 모듈이 아니며 실제 책임이 생길 때 필요한 패키지만 추가한다.

```text
backend-app/
  boot/                         실행·설정·의존성 조립
  user/
    com.pawbridge.backend.user
      api/                      다른 업무에 제공하는 계약
      internal/
        <기능>/
          domain/               업무 모델·규칙·필요한 포트
          application/          유스케이스 실행 조율
          web/                  REST·STOMP·SSE 등의 인바운드 어댑터
          infrastructure/       영속성·외부 연동 구현
  community/
    com.pawbridge.backend.community
      api/
      internal/<기능>/<역할>/
```

작은 업무에서는 `internal.domain`, `internal.application`처럼 기능 구분을 생략할 수 있다. `api`는 다른 업무 모듈이 호출하는 Java 계약이다. HTTP 요청·응답 변환은 `internal.<기능>.web`이 담당한다. 실제 필요 없이 인터페이스나 빈 계층을 만들지 않는다.

community는 user의 api만 참조할 수 있다. boot는 설정과 의존성 조립을 위해 두 업무의 구현체를 참조할 수 있다. Gradle의 `implementation`만으로 공개 Java 타입의 내부 접근을 막을 수 없으므로 바이트코드 검사도 수행한다.

## 의존 규칙과 검사 범위

`gradle/architecture.gradle`이 제공하는 다음 두 검사를 루트와 모든 모듈의 `check`가 수행한다.

- `verifyArchitecture`: 정확히 세 모듈과 허용된 프로젝트 의존 방향, 루트의 집계 책임, 단일 Boot 모듈을 검사한다. 직접 의존은 태스크 그래프 생성 전에도 확인해 금지 순환이 컴파일 태스크 순환으로 가려지지 않게 한다.
- `verifyBusinessPackages`: 실제 main 바이트코드를 읽어 업무별 api/internal과 domain/application 의존 경계를 검사한다. 테스트 코드는 실제 업무 구현으로 세지 않는다.

주요 패키지 규칙은 다음과 같다.

- api는 JDK와 허용된 api 계약만 참조한다. 내부 모델·저장소·JPA·프레임워크 타입을 반환값, 필드, 상속, 제네릭, 예외, 애너테이션 등을 통해 노출하지 않는다.
- domain은 자신의 domain과 JDK 타입만 참조한다. JDK에 포함된 JDBC·HTTP 타입도 거부한다. 다른 업무 api, 자신의 application·web·infrastructure, Spring·JPA·JDBC·HTTP 타입은 허용하지 않는다.
- application은 web·infrastructure 및 JPA·JDBC·Feign·HTTP 기술에 의존하지 않는다. Spring 예외는 `Transactional`, `Propagation`, `Isolation` 세 타입으로 제한한다. 이 예외가 있으므로 application 전체를 프레임워크 독립이라고 표현하지 않는다.

Gradle 검사는 compile/runtime/annotation processor 의존과 전이 경로를 확인한다. 실제 source·resource·compiler sourcepath가 모듈 내부에 있는지, 파일 classpath가 금지 모듈이나 형제 서비스의 출력으로 우회하는지도 검사한다. 모듈별로 분리한 중앙 출력은 허용하지만 소유권이 겹치는 출력은 거부한다.

이 검사는 Gradle 모델의 경로·의존 관계와 정적 바이트코드 경계를 다룬다. 임의 Gradle 스크립트를 격리하는 보안 샌드박스나 boot의 모든 코드가 조립만 하는지 판정하는 의미 검사는 아니다. 리플렉션·문자열 기반 접근과 실제 업무 책임은 코드 리뷰에서도 확인해야 한다.

domain의 JDK-only 규칙과 달리 application은 모든 외부 라이브러리를 거부하는 허용 목록을 사용하지 않는다. 명시한 어댑터 역할과 기술 패키지를 검사하므로, 그 목록에 없는 직렬화 라이브러리 등의 직접 의존까지 자동으로 차단한다고 보장하지 않는다. 실제 업무 편입 때 외부 타입의 역할을 검토하고 필요한 규칙·예외를 해당 계약과 함께 보완한다.

현재 user·community에는 업무 구현 클래스가 없다. 패키지 검사는 실제 업무 클래스 수를 출력하고 0개일 때 실제 업무 코드의 준수 여부가 미검증임을 명시한다. fixture 검증과 실제 기능 편입 완료를 구분한다.

현재 JDK-only 검사는 Java 단계의 기준이다. Kotlin 도입 시 컴파일러가 추가하는 Metadata 등 필수 언어 지원 타입과 기술 프레임워크 의존을 구분하고, 실제 Kotlin 허용·위반 fixture로 검사 규칙과 컴파일 입력 경계를 함께 조정해야 한다. 이 F1에 Kotlin 지원이나 언어 런타임 예외를 미리 추가하지 않는다.

## 현재 실행 동작

기본 설정은 비웹이다. `PawBridgeApplication`은 context를 기동하고 정상적으로 닫은 뒤 종료한다. HTTP·DB·Redis·Kafka·R2·OAuth·예약 설정이나 기존 서비스의 .env를 읽는 설정을 추가하지 않는다. context 테스트는 비웹 기동·종료와 datasource·예약 구성요소 부재를 확인한다. 이 테스트 자체가 외부 연결을 차단하는 방화벽은 아니다.

기존 기능의 REST·STOMP·SSE 전송 계약과 데이터·트랜잭션·권한은 실제 업무 편입 단계에서 검증한다. F1에서 컨트롤러나 공개 업무 API를 임의로 추가하지 않는다.

## 검증 실행 위치

운영 Mac에서는 Gradle·Java 앱·컨테이너를 실행하지 않는다. `Backend app check`의 GitHub-hosted runner에서만 다음 작업을 수행한다. backend-app 또는 이 workflow가 바뀐 dev 대상 PR과 dev push에만 읽기 권한의 검사를 실행한다. 이미지 게시·운영 비밀값·infra PR·배포 연결은 없다.

```sh
bash ./gradlew --no-daemon --max-workers=1 projects check :boot:bootJar
timeout 30s java -jar boot/build/libs/pawbridge-backend-app.jar
```

`boot:test`는 Spring context를, `boot:architectureTest`는 실제 production guard entry와 업무 build 스크립트를 사용하는 TestKit fixture를 검증한다. 허용·위반 fixture는 작은 Java 코드를 실제로 컴파일한다. 위반 사례는 의도한 guard의 거부를 확인하며 컴파일·의존성 다운로드 실패를 성공 근거로 취급하지 않는다.

architectureTest sourceSet은 일반 테스트·앱의 의존성을 상속하지 않는다. TestKit의 Gradle 로깅 구현과 Spring Logback 충돌을 피하기 위해 실행 classpath를 분리한다. fixture는 같은 CI의 Gradle 설치와 이미 해결한 의존 캐시를 사용하고 offline으로 실행한다. 전용 daemon은 192MiB부터 순차 재사용하며 바깥 빌드의 --no-daemon과 구분한다. 각 Test 태스크는 256MiB·fork 1개로 제한한다.

ArchUnit 1.5.1은 Gradle 검사 스크립트의 빌드 의존성이다. 앱의 compile/runtime 의존성으로 추가하지 않는다. CI는 실행 JAR에도 검사 도구·TestKit·JUnit이 포함되지 않았는지 확인하고 JDK·Gradle·runner 버전과 시험·정상 종료 근거를 보존한다.

CI 통과는 Mac ARM64 실제 기동, 기존 업무 통합 또는 메모리 절감의 증거가 아니다.

## 빌드 기준과 무결성

Java 21, Gradle 8.14.3, Spring Boot 3.4.11을 유지한다. 기존 앱의 Java 17 빌드·실행 설정은 변경하지 않는다. Wrapper JAR와 배포 ZIP의 공식 checksum을 고정한 기존 설정을 유지한다.

- [Gradle 배포 ZIP checksum](https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256)
- [Gradle Wrapper checksum](https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256)
- [ArchUnit 공식 사용 안내](https://www.archunit.org/userguide/html/000_Index.html)

전체 전환 결정·실행 기록은 Obsidian `Projects/pawbridge`의 기존 모듈형 모놀리스 전환 노트가 관리한다.
