# assignment-thymeleaf 비즈니스 규칙 후보 — '새 배포' 화면

> 대상: `legacy/assignment-thymeleaf/` · 범위: '새 배포' 화면(`GET /distributions/new`, `POST /distributions`)과 그 화면이 부르는 코드. 재배포 · 배포 목록 · 과제 목록 규칙은 다루지 않는다.
> 선행 문서: [ARCHITECTURE.md](ARCHITECTURE.md), [ERD.md](ERD.md) · 읽은 범위는 ARCHITECTURE.md 머리말과 같다(`DistributionService.java` 는 1–71줄만).
> 확신도 — **확실**: 조건문 · SQL · 설정으로 확인 / **추정**: 이름 · 주석 · 언어 · 프레임워크 동작으로 짐작(실행해 보지 않음).
> 실행한 것은 없다. 모든 "결과"는 코드를 읽고 판단한 것이다.

## 0. 먼저 볼 것 (주석 · 스키마 · 시드와 코드가 다른 곳)

| ID | 요약 |
|---|---|
| BR-01 | 주석은 "2024-03 QA 기간 중 임시 고정"이지만 고정값은 `2026-09-15 00:00:00` 이고, compose 로 띄우면 **이 고정 시각이 '현재'** 다. 마감 판정 · 배포 시각이 모두 여기에 묶인다 |
| BR-06 | 스키마 주석의 상태는 `O=진행 C=마감` 둘뿐인데, 코드는 `X`(연장) · `D`(삭제)에 의존한다. 반대로 `C`(마감) 상태 자체로 배포를 막는 코드는 없다 |
| BR-07 | DAO 주석은 "같은 학급 + 같은 과제는 한 건만"이라지만 UNIQUE 제약이 없고, **시드에 이미 같은 쌍이 2건** 있다 |
| BR-05 | 마감 지난 과제 거부는 **컨트롤러에만** 있다. `DistributionService.distribute` 는 마감을 보지 않는다 |

## 1. 시각 기준

### BR-01 '현재 시각'은 기본적으로 2026-09-15 00:00:00 고정
- **규칙**: 시스템 속성 `app.clock` 이 `system` 이 아니면 현재 시각을 `2026-09-15 00:00:00` 으로 본다. 마감 판정(BR-02 · BR-05)과 배포 시각 저장(BR-09)이 모두 이 값을 쓴다.
- **근거**: `AppClock.java:8-9`, `:14-20`, `DistributionController.java:44`, `:58`, `DistributionService.java:63`
- **근거 코드**:
  ```java
  // 2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)
  private static final String FIXED = "2026-09-15 00:00:00";
  ```
  ```java
  if (sys != null && sys.equals("system")) {
      return LocalDateTime.now().withNano(0);
  }
  return LocalDateTime.parse(FIXED, FMT);
  ```
- **확신도**: 확실 (분기는 코드, 컨테이너 기본값은 설정으로 확인 — `Dockerfile:11` ENTRYPOINT 와 `docker-compose.yml:50-66` 에 `app.clock` 없음, 저장소 grep 결과 `AppClock.java:15` 1건)
- **비고**: **주석과 코드 불일치** — 주석의 "2024-03"과 값 `2026-09-15` 가 다르다. 테스트가 이 고정값을 기대값으로 박아 두었다(`AppClockTest.java:12`). 이관 시 주입 `Clock` 으로 바꾸면 characterization 결과가 날짜에 따라 달라질 수 있으니 테스트 시각을 같은 값으로 고정해야 한다.

## 2. 과제 선택지 (`GET /distributions/new`)

### BR-02 선택지는 O · X · C 상태 과제, 마감 빠른 순. 마감 지났고 X 가 아니면 비활성
- **규칙**: 과제 선택지에는 상태가 `O` · `X` · `C` 인 과제를 마감 오름차순 · ID 오름차순으로 보여 주고, 마감이 현재 시각보다 이전이며 상태가 `X` 가 아니면 선택할 수 없게(`disabled`) 표시한다.
- **근거**: `AssignmentDao.java:34-38`, `distribution_form.html:13-16`
- **근거 코드**:
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "
  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
  ```html
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  ```
- **확신도**: 확실
- **비고**:
  - `D` 상태 과제는 선택지에 없다. 시드 기준(현재 `2026-09-15`)으로 과제 1 · 2 · 3 은 비활성, 4 · 5 · 6 만 선택 가능(`02-seed.sql:120-125`).
  - `disabled` 는 화면 표시일 뿐이다. 서버 쪽 같은 검사는 BR-05.
  - 라벨에 마감이 지났으면 ` [연장]`(X) 또는 ` [마감]` 을 붙인다(`distribution_form.html:18`) — 표시 전용.
  - `a.dueAt != null` 은 항상 참이다. `assignment.due_at` 이 `NOT NULL`(`01-schema.sql:86`).

### BR-03 학급 선택지는 전체 학급, ID 순
- **규칙**: 학급 선택지에는 모든 학급을 ID 오름차순으로 `이름 (교사ID)` 형식으로 보여 준다.
- **근거**: `ClassDao.java:21`, `distribution_form.html:25`
- **근거 코드**:
  ```java
  return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
- **확신도**: 확실
- **비고**: 교사 · 학년으로 거르지 않는다. 과제의 단원 학년과 학급 학년이 맞는지 검사하는 코드는 없다(`DistributionService.java:47-68`, `DistributionController.java:49-77` 전문 확인).

## 3. 배포 등록 검증 (`POST /distributions`)

검사 순서: ① 과제 존재(컨트롤러) → ② 마감(컨트롤러) → ③ 과제 존재(서비스) → ④ 학급 존재 → ⑤ 삭제 상태 → ⑥ 중복 → ⑦ 저장. 앞 검사에서 걸리면 뒤 메시지는 나오지 않는다.

### BR-04 없는 과제 · 없는 학급이면 거부
- **규칙**: 과제 ID 로 과제를 찾지 못하면 "존재하지 않는 과제입니다."로, 학급 ID 로 학급을 찾지 못하면 "존재하지 않는 학급입니다. (id=…)"로 거부하고 새 배포 화면으로 돌려보낸다.
- **근거**: `DistributionController.java:53-57`, `DistributionService.java:48-55`, `DistributionController.java:69-71`
- **근거 코드**:
  ```java
  if (a == null) {
      ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
      return "redirect:/distributions/new";
  ```
  ```java
  throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- **확신도**: 확실
- **비고**: 과제 존재 검사가 컨트롤러(`:53-57`)와 서비스(`DistributionService.java:48-51`)에 중복돼 있다. 이 화면에서는 컨트롤러가 먼저 걸러서, 서비스 메시지 "`(id=…)`" 판은 보이지 않는다.

### BR-05 마감 지난 과제는 배포 불가, 연장(X)만 예외
- **규칙**: 과제 마감이 현재 시각(BR-01)보다 이전이고 상태가 `X` 가 아니면 "마감(<마감 yyyy-MM-dd HH:mm>)이 지난 과제는 배포할 수 없습니다. [<제목>]"으로 거부한다.
- **근거**: `DistributionController.java:58-65`
- **근거 코드**:
  ```java
  // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
  ```
- **확신도**: 확실
- **비고**:
  - **컨트롤러에만 있다.** `DistributionService.distribute`(`:47-68`)에는 마감 검사가 없다 — 서비스를 다른 경로에서 부르면 마감 지난 과제도 배포된다. 이관 시 서비스로 옮길 것.
  - `isBefore` 는 엄격 비교라 마감 시각과 현재 시각이 **같으면 배포 가능**.
  - 메시지의 마감은 `yyyy-MM-dd HH:mm` 형식(`AppClock.java:12`, `:22-27`).
  - `getDueAt() != null` 은 항상 참(`01-schema.sql:86`).
  - 연장 여부는 `status = 'X'` 하나로만 판정한다. 연장 마감일 같은 별도 컬럼은 없다(`01-schema.sql:82-90`).

### BR-06 삭제(D) 과제는 배포 불가. 마감(C) 상태는 막지 않음
- **규칙**: 과제 상태가 `D` 면 "삭제된 과제는 배포할 수 없습니다."로 거부한다. 상태가 `C` 여도 마감 시각이 지나지 않았으면 배포된다.
- **근거**: `DistributionService.java:56-58`, `AssignmentDao.java:36`, `01-schema.sql:87`
- **근거 코드**:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
  ```sql
  status  CHAR(1)      NOT NULL DEFAULT 'O',        -- O=진행 C=마감
  ```
- **확신도**: 확실(`D` 거부, `C` 를 거부하는 조건 없음 — `src/main` 전체에서 `"C"` · `'C'` grep 결과 이 화면 경로는 `AssignmentDao.java:36` 1건) / 추정(`C` + 미래 마감 과제가 실제로 생기는지 — 시드에 없음)
- **비고**:
  - **스키마 주석과 코드 불일치** — 주석은 `O` · `C` 만 정의. `X` · `D` 는 코드(컨트롤러 주석 `:59`, 서비스 메시지 `:57`)에서만 의미를 알 수 있고 시드에도 없다(`02-seed.sql:119-125`).
  - `D` 는 선택지에 안 나오므로(BR-02) 조작된 요청에서만 이 검사에 닿는다. 마감이 지난 `D` 과제는 BR-05 에서 먼저 "마감" 메시지로 거부된다.
  - `C` 는 "마감"인데도 선택지에 나오고(`AssignmentDao.java:36`) 상태로 거부되지 않는다. 상태 `C` 와 `due_at` 이 어긋나면(마감 전인데 `C`) 배포된다. 의도인지 **미확인**.

### BR-07 같은 과제를 같은 학급에 두 번 배포 불가
- **규칙**: 같은 `(과제, 학급)` 배포가 1건 이상 있으면 "이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요."로 거부한다.
- **근거**: `DistributionService.java:59-61`, `DistributionDao.java:46-52`
- **근거 코드**:
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
      throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  ```
  ```java
  // 같은 학급 + 같은 과제 는 한 건만 존재해야 한다 (UNIQUE 제약은 없음, 여기서 막는다)
  ```
- **확신도**: 확실(검사 자체) / 추정(동시 요청 시 중복 발생)
- **비고**:
  - DB 에 UNIQUE 제약이 없다(`01-schema.sql:92-103`). 조회 후 삽입 사이에 잠금이 없어, 같은 쌍을 동시에 두 번 보내면 둘 다 통과할 수 있다(추정, 실행 안 함). 다만 BR-08 의 ID 충돌이 먼저 한쪽을 실패시킬 가능성도 있다.
  - **주석("한 건만")과 데이터 불일치** — 시드 배포 3 · 4번이 모두 `(과제 2, 학급 1)`(`02-seed.sql:133-134`). 이 규칙은 '새 배포' 경로에만 걸린다. 4번이 어떤 경로로 생겼는지는 재배포 코드(범위 밖)를 봐야 한다 — **미확인**.
  - `redistributed` 값과 무관하게 센다(`DistributionDao.java:49`).

## 4. 저장

### BR-08 배포 ID 는 현재 최댓값 + 1 (잠금 없음)
- **규칙**: 새 배포 ID 는 `distribution` 의 `MAX(id)`(없으면 0)에 1 을 더한 값이다.
- **근거**: `DistributionDao.java:54-57`, `DistributionService.java:62`
- **근거 코드**:
  ```java
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
  return (max == null ? 0L : max.longValue()) + 1L;
  ```
- **확신도**: 확실(채번 방식) / 추정(동시성 결과)
- **비고**: `distribution.id` 는 AUTO_INCREMENT 가 아니다(`01-schema.sql:93`). 문항 은행 등록은 `FOR UPDATE` 로 잠그지만(`legacy/item-bank-php/register.php:87`) 여기는 잠금이 없다. 동시 요청이면 같은 ID 로 INSERT → PK 충돌 → `DataAccessException` → "DB 오류로 배포에 실패했습니다."(BR-10)가 될 것으로 보인다(추정).

### BR-09 배포 시각은 앱 시각, 재배포 표시는 0
- **규칙**: 배포를 저장할 때 `distributed_at` 은 `AppClock.now()`(BR-01), `redistributed` 는 `0` 으로 넣고, 영향 행 수가 1 이 아니면 "배포 저장에 실패했습니다."로 실패 처리한다.
- **근거**: `DistributionService.java:63-66`, `DistributionDao.java:59-63`
- **근거 코드**:
  ```java
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
  ```java
  if (n != 1) {
      throw new IllegalStateException("배포 저장에 실패했습니다.");
  ```
- **확신도**: 확실
- **비고**: compose 기본 설정이면 모든 새 배포의 `distributed_at` 이 `2026-09-15 00:00:00` 으로 같다. DB `NOW()` 가 아니다. 매직 값 `0`.

## 5. 결과 · 오류 처리

### BR-10 성공은 배포 목록으로, 실패는 새 배포 화면으로 되돌림
- **규칙**: 배포에 성공하면 "배포가 등록되었습니다. (배포 #ID)"를 들고 배포 목록으로 가고, 검증 실패(`IllegalArgumentException` · `IllegalStateException`)는 그 메시지를, DB 오류(`DataAccessException`)는 "DB 오류로 배포에 실패했습니다."를 들고 새 배포 화면으로 돌아간다.
- **근거**: `DistributionController.java:66-76`
- **근거 코드**:
  ```java
  } catch (IllegalArgumentException | IllegalStateException e) {
      ra.addFlashAttribute("error", e.getMessage());
      return "redirect:/distributions/new";
  } catch (DataAccessException e) {
      ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
  ```
- **확신도**: 확실
- **비고**:
  - 컨트롤러의 과제 조회(`:53`)는 `try` 밖이라, 여기서 난 DB 오류는 `DbErrorAdvice` 로 가서 `db_error.html` 을 보여 준다(`DbErrorAdvice.java:11-16`) — 같은 "DB 오류"가 위치에 따라 다른 화면으로 간다. `GET /distributions/new` 의 DB 오류도 `db_error.html` 이다.
  - `db_error.html:8` 이 DB 원인 메시지(`getMostSpecificCause().getMessage()`)를 화면에 그대로 보여 준다.
  - `assignmentId` · `classId` 가 비었거나 숫자가 아니면 `@RequestParam long` 바인딩 실패로 스프링 기본 오류(400)가 날 것으로 보인다 — `DbErrorAdvice` 는 `DataAccessException` 만 잡는다(`:11`). 추정, 실행 안 함. 화면에서는 `required`(`distribution_form.html:11`, `:23`)가 막는다.

## 6. 주석 · 표시에만 있고 규칙으로 올리지 않은 것

| 내용 | 위치 | 비고 |
|---|---|---|
| `X` = 연장 | `DistributionController.java:59` 주석, `distribution_form.html:18` 라벨 | 스키마 주석(`01-schema.sql:87`)에 없음. 동작(BR-05 예외)은 코드로 확인 |
| `D` = 삭제 | `DistributionService.java:57` 메시지, `AssignmentDao.java:26` 주석 | 스키마 주석에 없음 |
| `C` = 마감 | `01-schema.sql:87` 주석 | 이 화면에서 `C` 로 분기하는 코드 없음(BR-06). 과제 목록 화면은 `C` 를 '종료'로 표시한다(`assignments.html:26`, 범위 밖 · grep 으로만 봄) |
| "다시 내려면 배포 목록에서 재배포를 사용" | `DistributionService.java:60` 메시지 | 재배포 조건은 범위 밖 — 확인 안 함 |
| 선택지 라벨 `MM-dd HH:mm` · `[연장]` · `[마감]` | `distribution_form.html:17-18` | 표시 전용 |

## 7. 매직 값

| 값 | 위치 | 의미 |
|---|---|---|
| `"2026-09-15 00:00:00"` | `AppClock.java:9` | 고정 현재 시각 |
| `"system"` | `AppClock.java:16` | 실제 시각을 쓰게 하는 `app.clock` 값 |
| `'O'` · `'X'` · `'C'` | `AssignmentDao.java:36` | 선택지 대상 상태 |
| `"X"` | `DistributionController.java:61`, `distribution_form.html:16` | 연장 — 마감 예외 |
| `"D"` | `DistributionService.java:56` | 삭제 — 배포 거부 |
| `0` (`COALESCE(MAX(id), 0)`) · `1L` | `DistributionDao.java:55-56` | 첫 배포 ID 를 1 로 |
| `0` (`redistributed`) | `DistributionDao.java:61` | 새 배포는 재배포 아님 |
| `1` (`n != 1`) | `DistributionService.java:64` | 저장 성공 행 수 |

## 8. 미확인 목록

| 항목 | 이유 |
|---|---|
| `C` 상태인데 마감 전인 과제를 배포 허용하는 것이 의도인지 (BR-06) | 기획 문서 · 이력 안 봄. 시드에 해당 사례 없음 |
| 시드 배포 3 · 4번 중복 쌍이 생긴 경로 (BR-07) | 재배포 코드(`DistributionService.java:72-701`) 범위 밖 |
| 동시 요청 시 중복 배포 · ID 충돌 결과 (BR-07 · BR-08) | 실행 안 함 |
| 파라미터 바인딩 실패 시 실제 응답 (BR-10) | 실행 안 함 |
| 재배포가 `C` 를 다르게 다루는지 | `DistributionService.java:190` 에 `case "C":` 가 있다(grep 으로만 봄, 범위 밖). 같은 상태가 경로별로 다르게 처리될 수 있음 |
| `AppClock` 고정 해제 시점 | 주석의 "2024-03"과 값 불일치. 이력 안 봄 |

## 9. 검증 이력

| 날짜 | 항목 | 바뀐 내용 | 근거 |
|---|---|---|---|
