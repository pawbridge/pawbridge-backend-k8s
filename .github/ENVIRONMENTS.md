# 백엔드 검사 전용 전환기

이 문서는 backend 저장소의 CI 실행 계약이다. main은 승인된 운영 릴리스의 소스 기준으로 정렬하고, dev는 모놀리스 전환 통합 기준으로 유지한다. 현재 기본 브랜치 변경이나 운영 교체가 완료됐다는 뜻은 아니다.

## 현재 허용하는 실행

- main/dev 대상 PR과 main/dev push는 필요한 서비스 검사와 정책 검사를 실행한다. 변경이 없는 서비스는 명시적으로 선택하지 않으며, 선택·검사 결과를 항상 생성되는 최종 check가 판정한다.
- `Environment` 정책 workflow는 경로 필터 없이 모든 `test_*.py` 계약 테스트를 실행한다. 최종 check 이름은 `Backend CI policy result`이며 테스트 실패·취소·예상 밖 skip을 통과로 바꾸지 않는다.
- 수동 입력을 제공하는 workflow는 `publish=false`인 검사만 허용한다. 누락·true·잘못된 입력과 허용되지 않은 ref/event는 발행 credential 단계 전에 거부한다.
- 이미지 발행·레지스트리 로그인·infra 저장소 쓰기 단계는 literal false로 동결한다. 앱/migration 이미지의 push도 false다. 검사 caller는 발행 credential을 전달하지 않는다.
- 기존 publisher 본문은 후속 검토 자료로 남긴다. 환경 flag나 false 한 줄을 뒤집는 방식으로 다시 활성화하지 않는다.
- 이 단계의 성공은 코드/JAR·정책 검사 근거다. Docker 이미지 빌드·발행·ARM64 실행이나 운영 배포 성공을 뜻하지 않는다.

옛 Actions run은 옛 정의를 사용한다. 새 정의의 무발행만으로 이미 시작한 job이나 과거 run 재실행까지 차단됐다고 판단하지 않는다. 전환 시 기존 발행의 진행·대기·수동/재실행 주체를 확인하고 필요한 별도 통제를 승인받는다. 이 변경은 token 삭제·교체나 운영 credential 변경을 포함하지 않는다.

## 브랜치와 검증 근거

공유 CI 작업은 pre-F1 기준에서 만들어 dev와 최초 main 기준선이 같은 CI head를 공유한다. main 기준선은 dev의 모놀리스 merge 결과에서 분기하지 않는다. 구체적인 base/head·합성 merge SHA·run을 각 PR에 기록한다. dev 성공을 다른 main 후보의 검증으로 대신하지 않는다.

main의 운영 수정은 일반 merge로 dev에 전달한다. 미완성 모놀리스가 있는 동안 dev 전체를 main에 일상 병합하지 않는다. 모놀리스 작업의 PR base와 작업 폴더 ref는 dev로 명시하고, 운영 hotfix는 승인된 운영 소스 기준을 명시한다. 기본 브랜치나 보호 설정이 바뀌었다는 이유로 현재 서버가 바뀌지 않는다.

## R4 발행과 운영 전환은 별도 승인

main 발행은 아직 활성화하지 않았다. 필요할 때 승인한 source SHA·검증 run·앱/migration digest·설정 묶음을 연결하는 수동 발행 계약을 별도로 검토한다. main merge가 운영 배포를 실행하지 않는다.

기존 컨테이너 CI의 linux/amd64 산출물과 실제 Mac 운영의 linux/arm64 경로를 구분한다. ARM64 manifest·플랫폼 digest 확인과 실제 ARM64 실행 확인도 별개다. multi-platform 빌드만으로 Mac 운영 호환 완료라고 기록하지 않는다. 운영 적용에는 대상·이전 image/config 보존·변경 후 확인과 최종 승인이 필요하다.

## 보존한 K8s/infra 경로의 의미

남아 있는 publisher 본문은 인프라 dev의 `environments/dev/isolated-values`와 환경 계약을 전제로 한다. `environments/dev/values` 같은 기존 runtime values를 갱신하는 허가는 아니다. backend 출처 ref와 infra 목적지 branch를 함께 치환하지 않는다.

과거의 dev digest→infra main 승격 및 GPU WSL 안내는 해당 K8s/별도 실행 경로의 맥락이다. 이를 현재 Mac 운영 배포의 증거로 사용하지 않는다. frontend·Python·infra 저장소, Argo·Tunnel·DB·CDC의 실행 주체는 이 변경으로 바꾸지 않는다.

## 로컬 dev 경로

인프라 저장소의 `scripts/environments/local_dev.py`와 `environments/dev/README.md`는 로컬 Compose 개발 절차다. `isolated-values`는 이미지 메타데이터이며 namespace 설정이 아니다. 이 backend CI는 PC에 직접 배포하지 않는다. 로컬 개발 기동도 운영 배포와 구분한다.
