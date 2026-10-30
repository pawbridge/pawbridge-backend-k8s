# 운영 Compose

Mac mini의 Compose 프로젝트 `pawbridge-prod` 정의다. 운영 폴더(Git 밖 비공개 폴더)에는
`origin/main` 커밋의 `compose.yaml` 사본과 비공개 `compose.env`([형식](compose.env.example))를 둔다.
비밀값은 `compose.env`가 가리키는 env 파일과 비밀 폴더에만 있다.

```sh
cd <운영 폴더>
dc() { docker compose -p pawbridge-prod -f compose.yaml --env-file compose.env --profile background --profile public-entry "$@"; }
dc up -d --no-build --pull never --dry-run   # 먼저 확인
```

## 템플릿 교체

1. 새 main 커밋의 사본을 `compose.yaml.candidate`로 꺼낸다(`git show <sha>:deploy/compose/compose.yaml`).
2. 지금 사본과 후보를 각각 `config --format json`으로 렌더링해 의도한 차이만 있는지 비교한다.
   렌더링 결과에는 env 파일의 비밀값이 들어가므로 값은 출력하지 않는다.
3. 기존 `compose.yaml`을 날짜 붙은 이름으로 백업하고 후보로 바꾼 뒤, 바뀐 서비스만
   `dc up -d --no-build --pull never --no-deps <서비스>`로 다시 만든다. 롤백은 백업을 되돌리고 같은 명령을 실행한다.
