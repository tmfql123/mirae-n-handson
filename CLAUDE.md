# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- 답변과 문서는 한국어로 쓴다.
- 이 저장소는 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)의 실습 저장소다. 도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 `STU-<숫자>`.

## 1. 빌드 · 테스트 명령

`modern/api` (Java 21 · Gradle)

```bash
./gradlew test                                              # 전체 테스트. H2(test 프로필)로 돌아 DB 없이 통과한다
./gradlew test --tests "com.example.item.ItemServiceTest"   # 단일 테스트 클래스
./gradlew bootRun   # :8080. compose `modern` 프로필 MariaDB 필요 — 이번에 검증하지 않음
```

`modern/web` (Node 22 · Vite)

```bash
npm ci                                            # 의존성 설치 (npm install 아님)
npm run lint && npm run typecheck && npm test     # 변경 후 세 명령이 모두 통과해야 한다
npx vitest run src/components/ItemTable.test.tsx  # 단일 테스트 파일
npm run build                                     # dist/ 생성
npm run dev                                       # http://localhost:5173
```

## 2. 코딩 컨벤션

### modern/api
- 패키지는 도메인별로 나눈다: `com.example.<도메인>` 안에 Entity · Repository · Service · Controller · DTO 를 둔다. 공통 코드는 `common/`, 설정은 `config/`.
- Controller 는 Service 만 주입받는다. Controller 파일에 `Repository` 가 import 되어 있으면 위반이다.
- Controller 에는 `try`/`catch` 가 없다. 예외는 `common/GlobalExceptionHandler` 가 매핑한다.
  - 대상이 없으면 `NotFoundException`(404), 잘못된 인자는 `IllegalArgumentException`(400), 상태 충돌은 `IllegalStateException`(409)을 던진다.
  - 오류 응답 본문은 `ErrorResponse` 한 가지다.
- 요청 · 응답 DTO 는 `record` 로 만든다. Controller 메서드의 반환 타입에 `@Entity` 클래스가 나오면 위반이다.
- Service 는 `@Transactional` 을 붙이고, 조회 전용은 `@Transactional(readOnly = true)`. 엔티티 → DTO 변환은 Service 메서드 안에서 끝낸다(`open-in-view: false`).
- 의존성은 생성자 주입만 쓴다. `src/main` 에 `@Autowired` 가 있으면 위반이다.
- 로그는 `private static final Logger log = LoggerFactory.getLogger(<클래스>.class)` 로 남긴다. `System.out` · `printStackTrace` 는 쓰지 않는다.
- 현재 시각은 주입받은 `Clock` 으로 구한다(`LocalDateTime.now(clock)`). 인자 없는 `now()` 는 쓰지 않는다.
- 테스트 클래스는 `@ActiveProfiles("test")` 를 붙이고, 테스트 메서드마다 한국어 `@DisplayName` 을 단다.
- 동작을 바꾼 main 클래스마다 `src/test` 의 같은 패키지에 `<클래스>Test.java` 가 있어야 한다.

### modern/web
- `fetch` 호출은 `src/api/client.ts` 에만 있다. 컴포넌트 · 훅에서 `fetch(` 가 나오면 위반이다.
- API 기본 주소는 `API_BASE`(`VITE_API_BASE`, 기본 `http://localhost:8080`)에서만 읽는다. 테스트 파일(`*.test.ts(x)`)과 주석을 빼면 `src/api/client.ts` 밖에 `localhost:8080` 을 쓰지 않는다.
- 데이터 조회 훅은 `src/hooks/use<이름>.ts`, 컴포넌트는 `src/components/<PascalCase>.tsx`.
- 테스트 파일은 대상 파일 옆에 `<이름>.test.tsx` / `<이름>.test.ts` 로 둔다. 네트워크는 `src/test/mockFetch.ts` 로 대체하고, 테스트 데이터는 `src/test/fixtures.ts` 에 둔다.
- `any` 금지 · 미사용 변수 금지(`_` 접두어만 허용) · 타입은 `import type` — `npm run lint` 가 강제하므로 lint 통과로 판정한다.
- TypeScript `strict` · `noUnusedLocals` · `noUnusedParameters` 는 켜 둔 채로 둔다. `npm run typecheck` 통과로 판정한다.

## 3. 금지 사항

- `legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.
- 의존성 설치에 `npm install` 을 쓰지 않는다. `package-lock.json` 은 바뀌지 않아야 한다.
- 루트 `.env` 를 만들지 않는다. 권한 실습용 더미는 `.env.perm-test` 다.
- `characterization/__snapshots__/` 와 `characterization/tests/` 를 새 API 를 통과시키려고 수정하지 않는다. 불일치는 이관 코드에서 고친다.
- `characterization/tests/` 에 `localhost:808x` 주소를 쓰지 않는다. 대상 주소는 `characterization/lib/target.mjs` 에만 있다.
- `mcp-skeleton/src/itemApi.ts` 를 수정하지 않는다. 도구 추가는 `mcp-skeleton/src/index.ts` 에서만 한다. 이 서버에서 `console.log` 를 쓰지 않는다(stdout 은 프로토콜 채널).
- `incident-logs/` 아래 원본 로그를 수정하지 않는다.
- `vendor-prs/*.patch` 를 `main` 브랜치에 적용하지 않는다. `review/pr-*` 브랜치에서만 적용한다.
- `pipeline-samples/terraform/` 에서 `terraform apply` · `destroy` · `state` · `import` 를 실행하지 않는다.
- DB 쓰기 계정 `app` 을 코드 · 명령에 쓰지 않는다. 조회는 `readonly` 계정으로 한다.

## 4. 아키텍처 안내

| 모듈 | 위치 | 스택 | 주소 |
|---|---|---|---|
| 문항 은행 (레거시) | `legacy/item-bank-php/` | PHP 7.4 mysqli | :8081 (compose `php`) |
| 과제 배포 (레거시) | `legacy/assignment-thymeleaf/` | Spring MVC + Thymeleaf + JDBC DAO | :8082 (compose `thymeleaf`) |
| 성적 집계 (레거시) | `legacy/grade-mssql/` | MS-SQL 저장 프로시저 + Java 호출부 | :8083 (compose `mssql`) |
| 현행 API | `modern/api/` | Spring Boot 3 · JPA | :8080 (로컬 실행) |
| 현행 화면 | `modern/web/` | React 18 · TS · Vite | :5173 (로컬 실행) |

- **DB**: compose `php` · `thymeleaf` · `modern` 프로필은 MariaDB 하나(`itembank`, :3306)를 공유한다. 스키마 · 시드는 `db/mariadb/init/`, MS-SQL(`grades`, :1433)은 `db/mssql/init/`. 시드는 고정 ID · 고정 시각이고, 데이터가 tmpfs 라 `down` 후 `up` 하면 시드 상태로 돌아간다. 읽기 계정: `readonly` / `readonly-pass` (MS-SQL 은 `Readonly-pass1`).
- **modern/api**: `item/`(문항 · 단원 · 태그), `assignment/`(학급 · 과제 · 배포 · 제출 · 리포트) 두 도메인. 스키마는 DB 가 관리한다(`ddl-auto: none`). Hikari 풀은 최대 5, 연결 대기 3초로 작게 잡혀 있다. 테스트는 `src/test/resources/application-test.yml`(H2, MariaDB 호환 모드).
- **modern/web**: `src/api/`(HTTP · 타입) → `src/hooks/`(조회 훅) → `src/components/`. API 쪽 `config/WebConfig` 가 5173 출처의 GET 만 CORS 허용한다. Vite `/api` 프록시는 `VITE_API_BASE=""` 일 때만 쓰인다.
- **characterization/**: 레거시 응답(HTML/JSON)을 `lib/normalize.mjs` 가 `{status, rows, count, message}` 로 정규화해 `__snapshots__/` 와 비교한다. 모듈 이름은 `item-bank` · `assignment` · `grade`(폴더명과 다름). `TARGET_BASE_URL` 로 같은 테스트를 새 API 에 돌리고, 경로가 바뀐 엔드포인트는 `lib/target.mjs` 의 `PATH_ALIASES` 에 기록한다.
- **legacy/grade-mssql**: 로직은 `sql/*.sql` 프로시저에 있고, 같은 프로시저가 `db/mssql/init/04-procs.sql` 에도 복사돼 있다. 한쪽을 바꾸면 다른 쪽도 같은 커밋에서 바꾼다.
- **그 밖의 폴더**: `specs/`(신규 개발 스펙 + `starters/java` · `starters/python` 골격), `templates/`(스택별 CLAUDE.md 템플릿 · 검증루프 · 체크리스트), `vendor-prs/`(외주 PR 패치), `incident-logs/`(장애 로그 a~d), `pipeline-samples/`(배치 로그 · 적재 건수 · Terraform), `ci-ports/`(실행해 보지 않은 CI 이식 예시), `mcp-skeleton/`(stdio MCP 서버 골격).
- **Hook 실행기**: `scripts/hook-node.sh` 는 node 를 찾아 Hook 스크립트를 실행하고, 못 찾으면 종료 코드 2 로 막는다.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않고, "의심 동작" 목록으로 따로 보고한다.
