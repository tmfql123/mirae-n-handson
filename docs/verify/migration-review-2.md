# 머지 전 검증 — migration-review-2

- 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, HEAD `a0ac2bf`, 변경 파일 28개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관. (이관 대상은 변경 내용상 `legacy/item-bank-php/search.php` → `GET /api/items/search`)
- 코드는 수정하지 않았다. 이 문서만 새로 썼다.
- 1차 검증(`migration-review-1.md`) 이후 코드 변경은 없다: `a0ac2bf` 는 그 문서만 추가한 커밋이다. 이번에는 1차 지적의 줄번호를 현재 파일 기준으로 다시 맞췄다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 (단서 있음) | `modern/api` `./gradlew cleanTest test` 73건 통과 · 실패 0 · 오류 0 · 건너뜀 0 (캐시 아님, 재실행). `characterization` `npm test`(레거시 :8081) 32/32 통과. `TARGET_BASE_URL=http://localhost:8080 npm test` 31 통과 · 1 건너뜀 · 실패 0 (건너뜀은 `example-units.test.js` 의 레거시 전용 `skipIf`, 이번 diff 가 만든 것이 아님). 단서는 "확인 필요" 1번 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. SQL 값은 `:name` 바인딩, 정렬식은 화이트리스트(이슈 3). 시크릿 리터럴 · 데이터 삭제 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 요청은 엔드포인트 1개 이관. 이관 코드(`modern/api` main 5 + test 8 + 테스트 SQL 2)와 동작 보존 테스트(`characterization/tests`, `__snapshots__`)는 설명된다. `.claude/skills/*`(3) · `CLAUDE.md` · `docs/assignment/*`(3) · `docs/item-bank/*`(4) · `docs/verify/migration-review-1.md` 는 설명되지 않는다. 이슈 1 |
| 컨벤션 준수 | 충족 | 위반 없음. Controller 는 Service 만 주입(`ItemSearchController.java:17-21`), `Repository` import · `try/catch` 없음. DTO 는 `record`(`ItemSearchResponse.java:8`, `ItemSearchRow.java:8`), 생성자 주입, 로거 사용(`ItemSearchService.java:16`), 인자 없는 `now()` · `System.out` 없음(grep 확인). `characterization/tests` 의 기존 파일 수정 없음 — 신규 추가분만 있다. 의존성 · 스키마 · 설정 파일 변경 없음 |

이슈 목록
| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `.claude/skills/impact/SKILL.md:1`, `docs/item-bank/BUSINESS-RULES.md:1`, `docs/assignment/BUSINESS-RULES.md:1` 외 docs 파일 | 이관과 무관한 도구 설정 · 문서 · 1차 검증 보고서가 같은 범위에 섞여 있다(diff stat 28개 중 이관 코드와 동작 보존 테스트를 뺀 약 10~13개 파일). 체크리스트 3번 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다"를 충족하지 못한다. `upstream/main` 이 `1bb4f2f` 에 머물러 있어 브랜치에서 앞선 작업이 통째로 잡힌 것으로 보인다 | 이관 코드 + 동작 보존 테스트만 별도 PR 로 분리하거나, 문서 · Skill 변경이 이번 범위라는 합의를 요청 문장에 명시한 뒤 재검증 |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:77-81` | 같은 `item` 패키지의 다른 조회는 JPA Repository 인데 검색만 `NamedParameterJdbcTemplate` 로 직접 SQL 을 쓴다. 뷰 · 콜레이션 의존(BR-01)이라 의도로 보이며 이유는 클래스 주석(`:68-71`)에만 있다 | 현 구조 유지 가능. 팀 합의가 필요하면 CLAUDE.md 에 "검색 조회는 JDBC" 예외를 한 줄 기록 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:92-94` (정렬식 출처 `ItemSearchCriteria.java:153-164`) | `ORDER BY`·`LIMIT`·`OFFSET` 을 문자열로 이어 붙인다. 값은 `orderBy()` 의 switch 고정 문자열과 `int` 뿐이라 주입 위험은 없다 | 현재는 안전. 정렬 키가 늘 때 화이트리스트를 벗어나지 않도록 `orderBy` 를 enum 으로 제한하면 더 견고 |

확인 필요
1. 테스트 실행 환경 — `TARGET_BASE_URL=http://localhost:8080` 통과가 현재 HEAD 로 빌드된 서버에 대한 결과인지 확인하지 못했다(:8080 은 200 응답, java 프로세스 시작 시각 12:30 만 확인, 빌드 시점 비교 불가). 사람이 `./gradlew bootRun` 으로 재기동한 뒤 새 API 대상 `npm test` 를 한 번 더 돌려야 한다. 이 확인 전까지 새 API 대상 통과는 잠정이다.
2. `modern/api/src/test/resources/sql/v_item_public.sql` — 테스트용 뷰 정의가 `db/mariadb/init/` 의 실제 `v_item_public` 정의와 같은지 비교하지 않았다. H2 통과가 MariaDB 동작을 보장하지 않는다.
3. 레거시 `search.php` 와의 줄 단위 대조(BR-03~14 · 콜레이션 정렬)는 하지 않았고 characterization 스냅샷 통과로 갈음했다. 스냅샷 밖 입력에 대한 동작은 미검증이다. 한 가지 확인한 점: `search.php:98` 도 LIKE 와일드카드(`%`, `_`)를 이스케이프하지 않고 신규 `ItemSearchRepository.java:112-113` 도 같아 동작은 일치한다.
4. "의심 동작" 목록 — `ItemSearchCriteria.java:11-12` 주석상 `level=3a → 3` 등 레거시 이상 동작을 그대로 옮겼다. CLAUDE.md 완료 기준(§5)이 요구하는 별도 "의심 동작" 목록 보고는 diff 에 없다.
5. 1차 문서의 줄번호 — `migration-review-1.md` 이슈 2·3 이 가리킨 `ItemSearchRepository.java:14,20` · `:34-35` 는 현재 파일과 맞지 않는다(실제 `:77-81` · `:92-94`). 이번 문서에서 바로잡았다.
