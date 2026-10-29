# PostgreSQL 암호화 백업과 격리 복원

운영 백업 컨테이너(`pawbridge-prod-backup-scheduler`) 이미지의 원본이다. 이미지에는 이 폴더의
`backup.py`, `restore.py`, `download.py`, `drill.py`와 시험이 들어간다. 예약 실행은
`deploy/scripts/mac_background_jobs.py loop`가 `deploy/config/backup-job.json`대로
12시간마다 `deploy/scripts/mac_backup_job.py`를 실행한다. 운영 기록은 Obsidian
`Projects/pawbridge/13 스토리지 백업과 복구`에 둔다.

## 백업 계약

- 대상은 PostgreSQL 17의 `pawbridge` DB 전체다. `pg_dump -Fc` 출력과 역할·멤버십·비밀번호 해시를
  `age` 공개키로 암호화한다. 평문 덤프 파일은 만들지 않는다.
- 덤프와 행 수 기준은 같은 snapshot에서 읽는다. 역할 해시가 전후로 달라지면 업로드하지 않는다.
- R2 버킷 `pawbridge-backups`의 `postgresql/v1/`만 사용한다. 실행마다 새 키에 암호문을 올리고
  다시 읽어 크기와 SHA-256을 확인한 뒤 `complete.json`을 마지막에 기록한다.
  완료 표시가 없는 묶음은 복원 후보가 아니다.
- Mac 작업은 목록 조회·덮어쓰기·삭제를 하지 않는다. 보관 기간은 버킷의 R2 lifecycle 규칙이 정한다.
  로컬 작업 폴더는 모든 객체를 확인한 뒤에만 지운다.
- 논리 백업이다. PITR, 물리 복제, 이미지 원본은 포함하지 않는다.

## 키와 자격 증명

- 컨테이너에는 age 공개키, R2 키, DB 비밀번호 파일만 마운트한다. `AGE-SECRET-KEY-...` 개인키는 Git, 로그,
  R2, 운영 컨테이너에 두지 않는다. 개인키를 잃으면 복원할 수 없다.
- 비밀번호와 키는 모두 `*_FILE` 경로로 전달한다. 명령행이나 로그에 값을 남기지 않는다.

## 장애 시 다운로드와 격리 복원

1. 완료된 실행 ID의 암호문만 새 폴더로 내려받는다. DB가 필요 없다.
   `R2_ENDPOINT`, `R2_ACCESS_KEY_FILE`, `R2_SECRET_KEY_FILE`을 준다.

   ```sh
   python3 /opt/backup/download.py --run <완료된 실행 ID> --destination /recovery/bundle
   ```

2. 같은 이미지의 **새 격리 컨테이너**(`--network=none`, 운영 볼륨 미연결, 비밀 파일 읽기 전용)에서
   새 폴더에 복원한다. postgres(UID/GID 999)만 개인키와 비밀번호 파일을 읽을 수 있게 준비한다.

   ```sh
   python3 /opt/backup/drill.py \
     --directory /recovery/bundle --identity /run/recovery/identity.txt \
     --app-password-directory /run/recovery/apps \
     --workdir /work/new-drill --report /work/drill-report.json \
     --allow-isolated-restore
   ```

- `--app-password-directory`에는 백업에 기록된 로그인 역할마다 역할 이름과 같은 비밀번호 파일을 둔다
  (현재 `pawbridge_app`, `pawbridge_vector`). 하나라도 없으면 복원 전에 거부한다. 복원 뒤에는 각 역할로
  로그인해 원본에서 읽을 수 있던 테이블을 다시 조회한다.
- 복원 폴더나 보고서가 이미 있으면 거부한다. 실패하면 새 대상으로 다시 시도한다.
- 복원은 운영으로 자동 전환하지 않는다. 최종 판단은 `drill.py` 보고서로 한다.

## 로컬 검증

```sh
docker build --pull=false -t pawbridge-postgresql-backup:validation deploy/backup
docker run --rm --pull=never --network=none --read-only --cap-drop=ALL \
  --security-opt=no-new-privileges --cpus=2 --memory=2g --pids-limit=256 \
  --tmpfs=/tmp:rw,nosuid,nodev,size=256m \
  --tmpfs=/work:rw,nosuid,nodev,size=512m,uid=999,gid=999 \
  -e PAWBRIDGE_BACKUP_TEST_CONTAINER=true --entrypoint=python3 \
  pawbridge-postgresql-backup:validation -m unittest discover -s tests -p 'test_*.py' -v
```

R2 전송 시험은 메모리 S3 대역이다. 실제 R2 연결이나 운영 데이터 복원을 증명하지 않는다.
