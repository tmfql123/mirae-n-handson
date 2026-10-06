# 머지 전 검증 — migration-review-3

- 대상: `git diff upstream/main...HEAD -- modern characterization` (브랜치 `day1`, HEAD `a9c4897`) **+ 작업 트리 미커밋 변경**. 즉 `git diff upstream/main -- modern characterization` 기준의 최종 작업 트리 상태다.
  - 커밋 범위 16개 파일: 베이스라인 `3eba02c`(characterization 테스트 · 스냅샷 신규), 이관 `7d8086c`(main 6 · test 6 · test SQL 2)
  - 미커밋 main 2개: `ItemSearchCriteria.java`(정렬 화이트리스트 · compact constructor · `parameter()` 개편 · `lastOnly()`), `ItemSearchRepository.java`(LIMIT/OFFSET 바인딩) — 2차 검증 이후 리뷰 지적 반영분
  - 미커밋 test 2개: 이번 검증 4단계에서 tester 가 추가(`ItemSearchCriteriaTest` 8개, `ItemSearchRepositoryTest` 1개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관. (`legacy/item-bank-php/search.php` → `GET /api/items/search`)
- 진행: 3단계(체크리스트 대조)는 reviewer Sub-agent, 4단계(테스트 실행)는 tester Sub-agent 에 위임했고, 메인 세션이 두 보고를 받아 최종 판정했다. main 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 (확인 필요) | `modern/api` `./gradlew test` 82건 통과 · 실패 0 · 건너뜀 0. `characterization` `npm test`(레거시 :8081) 32/32 통과. `TARGET_BASE_URL=http://localhost:8080 npm test` 31 통과 · 1 건너뜀(`example-units.test.js:18,21` 의 레거시 전용 `skipIf`, 이번 변경과 무관) · 실패 0 — **단, :8080 프로세스 시작 16:37:01 이 미커밋 소스 수정 시각(`ItemSearchRepository.java` 17:14:04, `ItemSearchCriteria.java` 17:18:22)보다 이르다.** 새 API 대상 통과는 수정 전 코드의 결과이므로 이 기준은 충족으로 표시하지 않는다. 확인 필요 1 |
| 치명 이슈 0건 | 미충족 | 이슈 1 (체크리스트 40행 문구 기준) |
| 요청 범위 이탈 없음 | 충족 | 요청 문장 1개 확인. 대상 파일 모두 search.php 이관 또는 그 동작 보존 검증(베이스라인)으로 설명된다. `v_item_public.sql` 은 운영 정의 `db/mariadb/init/01-schema.sql:52-70` 과 같은 H2 용 복제본. 의존성 · 설정 변경 없음. `characterization/lib` 변경 없음(`PATH_ALIASES` 의 `/search.php` 항목은 기존) |
| 컨벤션 준수 | 충족 | Controller 에 Repository import · try/catch 없음, record 반환. Service `@Transactional(readOnly = true)` · 생성자 주입 · Logger. `src/main` 에 `@Autowired` · 인자 없는 `now()` · `System.out` 없음. 테스트 클래스 `@ActiveProfiles("test")` · 한국어 `@DisplayName` |

이슈 목록
| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 치명 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:35` | `+ " ORDER BY " + criteria.orderBy()` — 입력(sort/dir)에서 나온 String 을 `+` 로 SQL 에 넣는 형태라 체크리스트 40행 문구에 걸린다. 40행에 예외 조항이 없고 16행이 재량 승인을 금지한다. 실질 주입 경로는 switch 리터럴(`ItemSearchCriteria.java` `orderBy()`)과 compact constructor 화이트리스트(`ItemSearchCriteria.java:28-33, 40-47`)로 닫혀 있다. LIMIT/OFFSET 은 이제 바인딩(`:36-39`)되어 2차 지적 중 그 부분은 해소 | (a) 정렬을 enum 으로 넘기고 Repository 의 enum `switch` 가 SQL 상수를 고르게 한다. 기존 테스트가 `orderBy()` 를 문자열로 비교하므로(`ItemSearchCriteriaTest` `buildsWhitelistedOrderBy`) 테스트 수정이 함께 필요. (b) 또는 체크리스트 40행에 "상수 집합에서 고른 ORDER BY 조각" 예외를 명문화 — 체크리스트 담당자 결정 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:85, 91-105` | 레거시와 다른 입력이 남아 있다. ① 평문 · 배열 키가 번갈아 섞인 요청(`tag=a&tag[]=b&tag=c`: PHP `c`, 신규 `b`) — MultiValueMap 이 키별로 묶어 순서가 사라진다(주석에 명시). ② `tag[]=a&tag[0]=b`: PHP 는 0번 칸을 덮어써 `b`, 신규는 첫 키 `tag[]` 의 `a`. characterization 케이스에 없는 입력이라 스냅샷으로 드러나지 않는다 | Controller 가 `HttpServletRequest#getQueryString()` 원문을 넘기고 PHP 규칙대로 순서 파싱. 고치지 않으면 알려진 차이로 문서화하고 사람이 수용 여부 결정 |
| 3 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:97` | 중첩 배열 키(`tag[a][b]=x`)는 PHP 에서 배열의 배열이 되어 `(string)reset()` 이 `"Array"`(notice)인데, 신규는 `startsWith("tag[") && endsWith("]")` 에 걸려 `x` 를 쓴다 | 첫 `[...]` 뒤에 `[` 가 또 오는 키는 `"Array"` 로 처리하거나 알려진 차이로 기록 |

reviewer 와 tester 보고의 차이 (판정에 반영한 방식)
- reviewer 는 "새 400 분기(`ItemSearchCriteria.java:40-47`)에 테스트 0건"(체크리스트 27행 미충족)과 "`parameter()` 변경 분기에 회귀 테스트 없음"을 지적했다. 두 Sub-agent 가 병렬로 돌아, reviewer 가 본 시점에는 tester 의 테스트 추가 전이었다.
- 메인 세션이 현재 파일에서 직접 확인했다: `ItemSearchCriteriaTest.java:141`(`rejectsOrderByOutsideWhitelist`) · `:150`(`rejectsNullOrderBy`) · `:157`(`rejectsNegativeOffset`) · `:90`(`usesPlainValueWhenPlainKeyComesAfterArrayKey`) · `:110`(`overwritesSameIndexedArrayKey`) 외, `ItemSearchRepositoryTest.java:101`(`bindsOffsetValue`). 82건 실행에 포함되어 통과했다.
- 시점 차이로 설명되는 차이라 해소된 것으로 보고 이슈에서 뺐다. 엇갈린 사실 판단(같은 시점의 상반된 결론)은 없었다.

확인 필요
1. 새 API 대상 characterization 재실행 — :8080 을 현재 작업 트리로 재기동(`cd modern/api && ./gradlew bootRun`)한 뒤 `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` 를 다시 돌려야 한다. 이 결과 전까지 기준 1 은 충족이 아니다.
2. `ItemSearchRepository.java:27, 34` 의 `query.where()` 연결 — 내용은 리터럴 조각과 `:named` 플레이스홀더뿐이지만 40행을 엄격히 읽으면 해당할 수 있다. 체크리스트 해석 필요.
3. DB 장애 시 응답 — 레거시는 오류 HTML 과 200(`search.php:712-729` 부근), 신규는 `GlobalExceptionHandler` 경유 5xx `ErrorResponse`. CLAUDE.md 컨벤션에 따른 의도한 차이로 이관 문서에 기록할지 결정 필요.
4. 태그 이름에 콤마가 있을 때 `ItemSearchRepository.java:42` `split(",", -1)` 과 `characterization/lib/normalize.mjs` 의 tags 분리 방식 일치 여부 미확인.
5. 추가 테스트 9개는 Claude(tester) 작성분이다. Codex 교차 리뷰를 요청했고 결과는 아래 "교차 리뷰" 절에 기록한다.

의심 동작 (레거시 동작을 그대로 옮김, 이관 중 수정하지 않음)
- `search.php:213-216` — 난이도 빈값이면 주석은 "1~5 모두 포함"이지만 실제는 `level < 5` 라 난이도 5 제외. 신규 `ItemSearchRepository.java:64-65` 도 같다.
- `search.php:223-229` — `3a`→3, `1e1`→10 등 PHP `(int)` 변환으로 범위 밖 난이도도 조회.
- `search.php:337` — page 정규식 `$` 가 끝 개행 하나를 허용해 `page=2\n` 을 2페이지로 받는다(신규 `ItemSearchCriteria.java:38`).
- `search.php:94-97` — 키워드의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작.
- 등록되지 않은 단원 · 태그도 거부하지 않고 0건으로 응답. 레거시의 경고 문구(`#warnings`)는 신규 응답에 없다(정규화 비교 대상 아님).

교차 리뷰
- 미커밋 main 2개 파일: Codex 교차 리뷰 2회 완료(1차 지적 — null `orderBy`, 인덱스 배열 키 — 반영 후 재리뷰에서 새 회귀 없음).
- tester 추가 테스트 9개: Codex 교차 리뷰 진행 중.
