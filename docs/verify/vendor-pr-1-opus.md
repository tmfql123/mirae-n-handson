# 외주 PR 검증 — vendor-prs/pr-1-missing-tests.patch

- 검증 대상: `vendor-prs/pr-1-missing-tests.patch` (적용하지 않고 patch 본문만 읽어 판정)
- 요청 범위: 단원별 문항 수 통계 API 추가
- 검증 기준: `templates/verification-loop.md` · `templates/approval-checklist.md` · 루트 `CLAUDE.md`
- 테스트: 요청에 따라 실행하지 않음
- 컨벤션 대조: `convention-check` Skill 은 작업 트리의 변경 파일을 대상으로 하는데 patch 를 적용하지 않아 스캔할 파일이 없으므로 쓰지 않았다. CLAUDE.md 2 · 3절과 patch 본문을 수동으로 대조했다.
- 줄번호 표기: `파일:줄` 은 patch 적용 후 파일 기준, 괄호 안 `patch:줄` 은 patch 파일의 줄번호

## 변경 파일 (`git apply --stat`)

| 분류 | 파일 | 변경 |
|---|---|---|
| modern/api | `src/main/java/com/example/item/UnitController.java` | +6 (엔드포인트 `GET /api/units/item-counts`) |
| modern/api | `src/main/java/com/example/item/UnitItemCountResponse.java` | 신규 +9 (응답 record) |
| modern/api | `src/main/java/com/example/item/UnitService.java` | +16 / −1 (`ItemRepository` 주입, `countActiveItemsByUnit()`) |
| modern/web · characterization · 기타 | — | 없음 |

`src/test` 아래 변경 파일은 0개다.

---

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 확인 필요 | 요청에 따라 `./gradlew test` 를 실행하지 않음. patch 커밋 메시지의 "기존 테스트 전부 통과 확인"(patch:14)은 검증되지 않은 자기 신고다. 별도로 체크리스트 1의 "테스트가 충분하다"는 미충족(이슈 #1) |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 · 문자열 SQL · 데이터 삭제/덮어쓰기 코드 없음(조회 전용, Spring Data 파생 쿼리 사용) |
| 요청 범위 이탈 없음 | 충족 | 요청 "단원별 문항 수 통계 API 추가" ↔ 변경 3파일(컨트롤러 엔드포인트 · 응답 DTO · 서비스 집계)이 모두 이 요청으로 설명된다. 의존성 · DB 스키마 · 설정 변경 없음 |
| 컨벤션 준수 | 미충족 | 이슈 #1 — 동작을 바꾼 `UnitController` 에 대응하는 `src/test/java/com/example/item/UnitControllerTest.java` 가 없다(CLAUDE.md 2절 "동작을 바꾼 main 클래스마다 같은 패키지에 `<클래스>Test.java`"). 이 파일 부재는 patch 이전부터지만 새 엔드포인트 추가로 규칙이 다시 적용된다. `UnitServiceTest` 는 파일이 있으므로 이 규칙 위반은 아니고, 새 메서드 미검증은 체크리스트 1 "테스트가 충분하다" 쪽 문제로 본다 |

네 기준 중 "컨벤션 준수"가 미충족이므로 반려한다. "테스트 통과"는 실행하지 않아 충족으로 표시하지 않았다.

이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/UnitService.java:36` (patch:78) · `UnitController.java:26` (patch:32) | 새 동작(`countActiveItemsByUnit()`, `GET /api/units/item-counts`)을 추가했지만 patch 에 `src/test` 변경이 하나도 없다. 기존 `src/test/java/com/example/item/UnitServiceTest.java` 는 `listUnits` 2건만 검증하고(테스트 파일 26 · 39행), `UnitControllerTest.java` 는 존재하지 않는다. CLAUDE.md 2절 "동작을 바꾼 main 클래스마다 `src/test` 의 같은 패키지에 `<클래스>Test.java` 가 있어야 한다" 위반, 체크리스트 1 "테스트가 충분하다" 미충족 | `UnitServiceTest` 에 `@Mock ItemRepository` 를 추가하고 ① 단원 순서 유지 ② 문항 0개 단원이 0 으로 포함 ③ `ItemStatus.ACTIVE` 로만 센다(`verify(itemRepository).countByUnitIdAndStatus(id, "A")`) ④ 단원 없음 → 빈 목록 케이스를 한국어 `@DisplayName` 과 함께 추가. `UnitControllerTest`(`@WebMvcTest`, `@ActiveProfiles("test")`)를 새로 만들어 `/api/units/item-counts` 의 상태 코드 · JSON 필드(`code`, `name`, `grade`, `activeItemCount`)를 검증 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/UnitService.java:37-39` (patch:79-81) | 단원 목록을 읽은 뒤 단원마다 `itemRepository.countByUnitIdAndStatus(...)` 를 호출한다 → 단원 N 개면 COUNT 쿼리 N+1 번. 지금 시드 규모에선 동작하지만 단원이 늘면 응답 시간이 단원 수에 비례해 늘어난다 | `ItemRepository` 에 `select i.unit.id, count(i) from Item i where i.status = :status group by i.unit.id` 형태의 집계 쿼리(파라미터 바인딩) 1개를 두고, 서비스에서 단원 목록과 `Map<unitId, count>` 를 합쳐 없는 단원은 0 으로 채운다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/UnitItemCountResponse.java:4` (patch:46) | 같은 도메인의 `UnitResponse` 는 `id, code, name, grade` 를 내보내는데(`UnitResponse.java:4`) 이 DTO 는 `id` 를 뺐다. 화면에서 두 응답을 단원 단위로 합칠 때 키가 달라진다 | 의도된 생략이 아니면 `Integer id` 를 첫 필드로 추가해 `UnitResponse` 와 필드 구성을 맞춘다 |

경고 · 제안 수준까지 다시 훑은 결과, 위 외의 컨벤션 항목은 충족이다: 컨트롤러는 `UnitService` 만 주입(Repository import 없음), 컨트롤러에 `try`/`catch` 없음, DTO 는 `record`, 반환 타입에 `@Entity` 없음, 클래스 수준 `@Transactional(readOnly = true)`(`UnitService.java:10`, 기존)이 새 메서드에도 적용되고 엔티티 → DTO 변환이 서비스 안에서 끝남, 생성자 주입만 사용, `LoggerFactory` 로거 사용, `System.out` · `now()` 사용 없음.

확인 필요

- 테스트 통과 — 요청에 따라 `modern/api` 에서 `./gradlew test` 를 실행하지 않았다. 판정 전 사람이 실행해 통과 · 실패 수를 확인해야 한다. 참고로 기존 `UnitServiceTest` 는 `@InjectMocks` 로 생성되는데 생성자에 `ItemRepository` 인자가 추가돼도(`UnitService.java:18`) Mockito 가 없는 mock 자리에 `null` 을 넘기므로 컴파일 · 기존 2건은 깨지지 않을 것으로 보이나, 실행으로 확인하지 않았다. `WebConfigTest`(`@WebMvcTest(UnitController.class)` + `@MockBean UnitService`)도 영향 범위에 있다.
- 공개 문항 정의 — 집계 대상을 `ItemStatus.ACTIVE`("A")만으로 잡았다(`UnitService.java:39`). `REVIEWING`("R") 문항을 제외하는 것이 요청자가 말한 "문항 수"의 의도와 맞는지 요청자 확인이 필요하다(코드는 `ItemStatus` 주석의 "외부에 노출하는 문항은 ACTIVE 만" 규칙과는 일치).
