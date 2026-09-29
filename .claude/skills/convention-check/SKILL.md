---
name: convention-check
description: 변경 파일(또는 지정 경로)이 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지켰는지 예/아니오로 판정하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 데 있나" 같은 요청이나, 커밋 · PR · 리뷰 직전에 쓴다. 코드는 고치지 않는다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

## 원칙
- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기
- 인자가 있으면(`$ARGUMENTS`) 그 경로만 본다. 디렉터리면 Glob 으로 파일을 펼친다.
- 인자가 없으면 저장소 루트에서 두 명령의 결과를 합친다.
  - `git status --porcelain` — 수정 · 추가 · 아직 add 하지 않은 새 파일(`??`) 포함
  - `git diff --name-only HEAD` — 스테이징 여부와 무관한 수정 파일
- 삭제된 파일(`D`)은 내용 점검에서 빼되, 8번(금지 경로) 판정에는 넣는다.
- 대상이 0개면 "점검할 변경 없음"만 출력하고 끝낸다.

## 2. 점검 항목 (예 = 통과)
각 항목은 적용 범위에 해당하는 대상 파일에만 적용한다. 해당 파일이 없으면 "해당 없음".

| # | 적용 범위 | 판정 질문 |
|---|---|---|
| 1 | `modern/api/**/*Controller.java` | `Repository` import 가 없는가? |
| 2 | 위와 같음 | `try` / `catch` 블록이 없는가? |
| 3 | `modern/api/src/main/**` | `@Autowired` 가 없는가? |
| 4 | 위와 같음 | `System.out` · `System.err` · `printStackTrace` 가 없는가? |
| 5 | 위와 같음 | 인자 없는 `now()`(`LocalDateTime.now()` · `LocalDate.now()` · `Instant.now()` 등)가 없는가? `now(clock)` 은 통과 |
| 6 | `modern/api/src/test/**/*Test.java` | 클래스에 `@ActiveProfiles("test")` 가 있고, 모든 `@Test` 메서드에 `@DisplayName` 이 있는가? |
| 7 | `modern/web/src/**` 중 `src/api/client.ts` 제외 | `fetch(` 가 없는가? 그리고 `*.test.ts(x)` · 주석을 빼고 `localhost:8080` 이 없는가? |
| 8 | 대상 파일의 경로 | `legacy/` · `incident-logs/` · `characterization/__snapshots__/` · `characterization/tests/` · `mcp-skeleton/src/itemApi.ts` · `package-lock.json` · 루트 `.env` 가 없는가? |

판정 방법:
- Grep 을 `-n` 으로 돌려 줄 번호를 얻고, 걸린 줄은 Read 로 앞뒤를 확인해 주석 · 문자열 안이면 제외한다.
- 6번은 `@Test` 줄마다 바로 위 애너테이션 묶음에 `@DisplayName` 이 있는지 본다. 없으면 그 `@Test` 줄을 근거로 댄다.
- 8번은 파일 내용이 아니라 경로로 판정하고, 근거는 `git status` 줄(예: `?? legacy/x.php`)로 댄다.
- 패턴은 걸렸지만 위반인지 코드만으로 확정할 수 없으면(예: 생성된 코드, 다른 파일의 정의에 좌우됨) "확인 필요"로 분류한다.

## 3. 출력 형식 (순서 고정)

### 판정 요약
- 점검 대상: N개 파일 (인자 지정 / git 변경분)
- 결과: 통과 a · 위반 b · 해당 없음 c · 확인 필요 d
- 항목별 한 줄: `1 통과` / `2 위반 2건` / `6 해당 없음` …

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../FooController.java:7` | 1. Controller 에 Repository import | Service 메서드로 옮기고 Service 만 주입 |

- 위반이 없으면 표 대신 "위반 없음" 한 줄.
- "확인 필요" 항목은 표 아래에 `- 파일:줄 — 확인할 내용` 형식으로 따로 적는다.
