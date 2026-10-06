# 머지 전 검증 — migration-review-1

- 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, 커밋 3개: 3eba02c · 4ade8fb · 7d8086c)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관. (이관 대상은 변경 내용상 `search.php` → `GET /api/items/search`)
- 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 (단서 있음) | `modern/api` `./gradlew test` 73건 통과 · 실패 0 · 건너뜀 0. `characterization` `npm test`(레거시 :8081) 32/32 통과. `TARGET_BASE_URL=http://localhost:8080 npm test` 31 통과 · 1 건너뜀(실패 0). 건너뜀은 `example-units.test.js:21` 의 `it.skipIf(legacyOnly)` — 이번 diff 가 만든 것이 아니고 의도된 레거시 전용 케이스. 단서는 "확인 필요" 1번 참조 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음 (SQL 은 값 바인딩, 정렬식은 화이트리스트 — 이슈 3 참조) |
| 요청 범위 이탈 없음 | 미충족 | 요청은 엔드포인트 1개 이관. 변경 27개 중 이관 코드(`modern/api` main 6 + test 8 파일)와 동작 보존 테스트(`characterization/`)는 설명되지만 `.claude/skills/*`(3) · `CLAUDE.md` · `docs/assignment/*`(3) · `docs/item-bank/*`(4)는 설명되지 않음. 이슈 1 |
| 컨벤션 준수 | 충족 | 위반 없음. Controller 는 Service 만 주입, `try/catch`·`Repository` import 없음, DTO 는 `record`, 생성자 주입, 로거 사용, `now()` 없음, 테스트 6개 클래스 모두 `@ActiveProfiles("test")` + `@DisplayName`. `@Autowired` 는 `src/test` 에만 있음(금지 대상은 `src/main`) |

이슈 목록
| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `docs/item-bank/BUSINESS-RULES.md:1` 외 docs · skills 파일 | 이관 요청과 무관한 도구 설정 · 문서 · 규칙 파일이 같은 범위에 섞여 있다 (diff stat 기준 약 2,000줄). 체크리스트 3번 "모든 파일이 요청 문장으로 설명된다"를 충족하지 못한다. `upstream/main` 이 1bb4f2f 에 머물러 있어 브랜치의 앞선 작업이 통째로 잡힌 것으로 보인다 | 이관 코드 + 동작 보존 테스트만 PR 로 분리하거나, 문서 · Skill 변경이 이번 PR 범위라는 합의를 요청 문장에 명시한 뒤 재검증 |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:14,20` | 같은 `item` 패키지는 JPA Repository(`ItemRepository`)를 쓰는데 검색만 `NamedParameterJdbcTemplate` 로 직접 SQL 을 쓴다. 뷰 · 콜레이션 의존이라 의도로 보이나 이유가 클래스 주석에만 있다 | 현 구조 유지 가능. 팀 합의가 필요하면 CLAUDE.md 에 "검색 조회는 JDBC" 예외를 한 줄 기록 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:34-35` | `ORDER BY`·`LIMIT`·`OFFSET` 을 문자열로 이어 붙인다. 값은 `ItemSearchCriteria.orderBy()`(`ItemSearchCriteria.java:153-164` switch 화이트리스트)와 `int` 뿐이라 주입 위험은 없다 | 현재는 안전. 정렬 값이 늘어날 때 화이트리스트를 벗어나지 않도록 `orderBy` 를 enum 으로 제한하면 더 견고 |

확인 필요
- 테스트 실행 환경 — `TARGET_BASE_URL=http://localhost:8080` 실행이 통과했으나 :8080 에 떠 있던 서버가 현재 HEAD 로 빌드된 것인지 확인하지 못했다(기동 시각 · 빌드 미확인). 사람이 `./gradlew bootRun` 으로 재기동한 뒤 한 번 더 돌려 확인해야 한다.
- `modern/api/src/test/resources/sql/v_item_public.sql` — 테스트용 뷰 정의가 `db/mariadb/init/` 의 실제 `v_item_public` 정의와 같은지 비교하지 않았다. H2 통과가 MariaDB 동작을 보장하지 않는다.
- 레거시 `search.php` 와의 줄 단위 대조(BR-03~14 · 콜레이션 정렬)는 하지 않았고 characterization 스냅샷 통과로 갈음했다. 스냅샷 밖 입력에 대한 동작은 미검증.
- "의심 동작" 목록 — `ItemSearchCriteria.java:11-12` 주석상 `level=3a → 3` 등 레거시 이상 동작을 그대로 옮겼다. 별도 목록 보고는 diff 에 없다.
