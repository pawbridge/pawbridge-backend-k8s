# PawBridge Backend (Kubernetes Edition)
<img width="1919" height="920" alt="Image" src="https://github.com/user-attachments/assets/2d016c69-7d91-4f1a-8111-abbb773ec5a7" />
반려동물 입양 프로세스, 커뮤니티, 스토어 통합 커머스 플랫폼인 PawBridge의 마이크로서비스 백엔드 저장소입니다. 
기존 AWS EC2 다중 노드 환경에서 운영되던 서비스를 개인 로컬 PC 기반의 Kubernetes(K8s) 클러스터로 마이그레이션한 버전입니다.

## 핵심 기술 아키텍처 (Key Architecture)

본 프로젝트는 대용량 트래픽과 데이터 정합성 보장을 위한 IT 기업의 실무 기술 스택을 적용하는 데 집중하였습니다.

### 1. CQRS & Transactional Outbox Pattern (데이터 동기화)
- Problem: 마이크로서비스 환경에서 RDB(MySQL) 저장과 검색엔진(Elasticsearch) 인덱싱 간의 분산 트랜잭션 실패 시 데이터 불일치 발생 방지
- Solution (CDC + Kafka): 
  - MySQL 테이블의 변경점만 Binlog로 읽어들이는 Debezium Source Connector 활용
  - 이벤트를 Apache Kafka 메시지 큐로 발행 후, Elasticsearch Sink Connector가 실시간으로 문서를 생성/업데이트
  - 애플리케이션에서는 MySQL 트랜잭션 내에서 outbox 테이블에만 기록(Outbox Pattern)하도록 하여 메시지 유실률 0% 달성 및 시스템 결합도 완화

### 2. Redis 기반 분산 캐시 및 인증 처리
- 다중 인스턴스로 돌아가는 유저 서비스 환경에서 상태를 공유하기 위해 Redis를 분산 메모리로 적극 활용
- 이메일 인증 통과 시 임시 인증 코드의 만료 기한(TTL) 설정 및 토큰 관리에 사용하여 빠른 I/O 속도 확보

### 3. Elasticsearch 기반 한국어 검색 최적화
- Nori (한글 형태소 분석기) 플러그인을 적용하여 유기동물 품종, 특징, 상품 카테고리를 자연어로 정확히 검색할 수 있도록 커스텀 인덱스 맵핑 및 Ingest 파이프라인 구축

### 4. Spring Batch 데이터 파이프라인
- 농림축산식품부 유기동물 공공데이터(APMS)를 주기적으로 자동 수집(`@Scheduled`), 정제 후 자체 DB 및 Elasticsearch로 동기화하는 대용량 배치 프로세스 구현

### 5. Monitoring & Observability
- Prometheus + Grafana + Zipkin 자체 호스팅으로 5개 마이크로서비스 통합 모니터링 구축
- JVM 힙/GC/스레드, HTTP p95·p99·RPS·에러율, Node Exporter 기반 CPU·메모리 통합 관리
- k6 부하 테스트(100 VUs, 5분) 결과를 Prometheus remote write로 Grafana에 실시간 연동
- **결과: 100 VUs 풀 부하 구간 Peak RPS 58.2/s, 에러율 0% 달성 · Worker 노드 메모리 92% 포화 문제 포착 및 해소**
<img width="1920" height="918" alt="Image" src="https://github.com/user-attachments/assets/4838b2c7-4d34-466e-b9db-2249253cd0a1" />
---

## 마이그레이션 배경 (Why K8s & Local VM)
<img width="1041" height="786" alt="Image" src="https://github.com/user-attachments/assets/bd6107f9-3596-42dd-a58b-f8aa744f6f5e" />
기존 AWS 무료 티어 기간 종료와 맞물려, 대규모 트래픽 처리에 필수적인 K8s 기반 MSA 환경과 Kafka를 직접 구축해보기 위해 로컬 수준의 클러스터로 이전했습니다.

1. AWS EKS 비용 문제 우회: 관리형 서비스인 EKS의 유지 비용이 개인 프로젝트 수준에서는 매우 부담스러웠습니다. 이에 대한 대안으로 보유 중인 고사양 로컬 PC(Ryzen 7800X3D, 64GB RAM) 인프라를 활용하여 직접 클러스터를 프로비저닝(Vagrant)하였습니다.
2. 실무 기술 내재화: 단순한 클라우드 VM 배포 경험을 넘어, Kubernetes 기반의 컨테이너 오케스트레이션과 Kafka 비동기 통신을 밑바닥부터 겪어보며 MSA 트러블슈팅 역량을 기름이 주된 목적입니다.

---

## Tech Stack
- Framework: Java 17, Spring Boot 3.x, Spring Cloud Gateway
- Database: 
  - MySQL 8.x: 서비스별 분산 RDB
  - Redis: 일회성 인증 데이터 관리 및 분산 캐시
- Data Pipeline: Apache Kafka, Debezium (CDC), Kafka Connect
- Search Engine: Elasticsearch 8.x
- Infrastructure: Kubernetes (Vagrant Local VM), Helm, Docker, Nginx Ingress

## Microservices
| Service | Internal Port | Description |
|---|---|---|
| api-gateway | 8080 | 공통 라우팅, JWT 인증 인가 필터링, 로드밸런싱 |
| user-service | 8080 | 회원가입, 로그인, JWT 토큰 발급, 이메일 인증 |
| animal-service | 8081 | 공공데이터(APMS) 유기동물 배치 수집, 검색, 관심 등록 |
| community-service | 8082 | 커뮤니티 피드, 게시글 및 댓글 작성 |
| store-service | 8083 | 이커머스 상품 관리, 장바구니, 검색 (Outbox 연동) |
| payment-service | 8084 | 토스페이먼츠(Toss) 연동 결제 처리, 주문 내역 관리 |

---

## 인프라 구축 매뉴얼 (Infrastructure Setup)
- 애플리케이션이 올라갈 Kubernetes 클러스터 하부 망(Vagrant, DB, Kafka 등) 설치 가이드는 전용 저장소인 [pawbridge-infra-k8s](https://github.com/pawbridge/pawbridge-infra-k8s)에서 확인할 수 있습니다.
- 동물 및 상품 검색 코어가 의존하는 Elasticsearch 수동 인덱스 매핑 파일 및 파이프라인 스크립트는 현재 백엔드 저장소의 infrastructure/elasticsearch/ 디렉토리에 포함되어 있습니다.

---

## 테스트 계정 안내 (Test Account)
로컬 클러스터 구동 또는 실제 배포 환경 접속 시 즉각적인 기능 테스트를 위해 아래의 관리자(Admin) 계정을 제공합니다.
- Admin ID (Email): admin@pawbridge.kr
- Password: password123!
