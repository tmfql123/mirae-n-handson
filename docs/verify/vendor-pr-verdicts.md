# 외주 PR 판정 기록

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| 단원별 문항 수 통계 API 추가 (`GET /api/units/item-counts`, HEAD~1..HEAD) | 반려 | 테스트 통과(테스트가 충분하다), 컨벤션 준수(동작을 바꾼 main 클래스마다 `<클래스>Test`) | `modern/api/src/main/java/com/example/item/UnitService.java:36-42` (신규 `countActiveItemsByUnit` — 변경분에 테스트 0건, `UnitServiceTest` 에 해당 케이스 없음), `UnitController.java:25-28` (신규 엔드포인트 — `UnitControllerTest` 없음, `itemCounts` 호출 테스트 없음), `UnitItemCountResponse.java:6` (신규 DTO — 응답 매핑 테스트 없음). 경고: `UnitService.java:38-39` 단원마다 `countByUnitIdAndStatus` 를 호출해 단원 N개 → 쿼리 N+1회 | 1) `UnitServiceTest` 에 `countActiveItemsByUnit` 케이스 추가: 학년·코드 정렬 순서, 문항 없는 단원 0 포함, DELETED 문항 제외. 2) 컨트롤러 테스트로 `GET /api/units/item-counts` 200 · 응답 필드(`code`,`name`,`grade`,`activeItemCount`) 검증, 모든 테스트에 한국어 `@DisplayName`·`@ActiveProfiles("test")`. 3) (권장) 단원별 반복 count 대신 `GROUP BY unit_id` 집계 쿼리 1회로 교체. |

검증 메모: `./gradlew test` (modern/api) 전체 통과, 실패 0 · 오류 0 · skip 0 (16개 스위트). 단, 변경분을 검증하는 테스트가 없어 "통과"가 신규 코드의 정상 동작을 보증하지 않는다. 치명 이슈 없음, 요청 범위 이탈 없음(변경 3파일 모두 요청 설명 가능).
