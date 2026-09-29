# assignment-thymeleaf 아키텍처 — '새 배포' 화면 (진입점 · 의존 관계)

> 대상: `legacy/assignment-thymeleaf/` · **범위: '새 배포' 화면 하나(`GET /distributions/new`, `POST /distributions`)와 그 화면이 부르는 코드까지.**
> 범위 밖: 과제 목록(`/`, `/assignments`), 배포 목록(`GET /distributions`), 재배포(`POST /distributions/{id}/redistribute`, `DistributionService.java:72-701`).
> 읽은 범위(전문): `DistributionController.java`, `DbErrorAdvice.java`, `AppClock.java`, `AssignApplication.java`, `dao/AssignmentDao.java`, `dao/ClassDao.java`, `dao/DistributionDao.java`, `model/AssignmentRow.java`, `model/ClassRow.java`, `templates/distribution_form.html`, `templates/layout.html`, `templates/db_error.html`, `application.properties`, `build.gradle`, `Dockerfile`, `src/test/.../AppClockTest.java`. `DistributionService.java` 는 **1–71줄만** 읽었다.
> 읽지 않음: `AssignmentController.java`(매핑 grep 만), `assignments.html` · `distributions.html`(링크 grep 만), `model/DistributionRow.java` · `SubmissionRow.java` · `RedistributeResult.java`, `DistributionService.java:72-701`.
> 실행한 것: 없음. 모두 코드를 읽고 판단했다.

## 1. 모듈 개요

- Spring Boot 3.3.4 · Java 17 · Thymeleaf · `spring-boot-starter-jdbc`(JdbcTemplate) 로 만든 과제 배포 화면이다 (`build.gradle:3`, `:11`, `:25-28`). JPA 는 없다.
- 앱 버전 `1.4.2` (`build.gradle:8`, 화면 꼬리말 `layout.html:35`).
- 컨테이너 안에서 8080 으로 뜨고(`application.properties:1`, `Dockerfile:10`), compose `assignment` 서비스가 호스트 **8082** 로 내보낸다 (`docker-compose.yml:53-54`).
- DB 는 MariaDB `itembank`(문항 은행과 같은 DB)에 쓰기 계정 `app` 으로 붙는다 (`application.properties:2-4`, `docker-compose.yml:61-63`).
- 커넥션 풀은 최대 4, 대기 5000ms (`application.properties:6-7`).

## 2. 진입점 (범위 안)

| HTTP | 경로 | 처리 메서드 | 결과 |
|---|---|---|---|
| GET | `/distributions/new` | `DistributionController.form` (`DistributionController.java:40-47`) | `distribution_form.html` 렌더 |
| POST | `/distributions` | `DistributionController.create` (`DistributionController.java:49-77`) | 성공 → `redirect:/distributions`, 실패 → `redirect:/distributions/new` + flash `error` |

- 화면에 들어오는 경로: 상단 메뉴 "새 배포" 링크 (`layout.html:26`).
- 폼은 `assignmentId` · `classId` 두 값을 `POST /distributions` 로 보낸다 (`distribution_form.html:8`, `:11`, `:23`).
- 예외 진입점: `DbErrorAdvice.dbError` (`DbErrorAdvice.java:11-17`) — 컨트롤러 밖으로 나온 `DataAccessException` 을 `db_error.html` 로 보낸다.

## 3. 의존 관계 (호출 방향 A → B = A 가 B 를 부름)

```
distribution_form.html ─th:replace→ layout.html (head · nav · flash · footer)   distribution_form.html:3,5,7,32

DistributionController.form ─→ AssignmentDao.findAllForSelect   DistributionController.java:42
                            ─→ ClassDao.findAll                 :43
                            ─→ AppClock.now                     :44

DistributionController.create ─→ AssignmentDao.findById         DistributionController.java:53
                              ─→ AppClock.now / AppClock.fmt    :58, :62
                              ─→ DistributionService.distribute :67

DistributionService.distribute ─→ AssignmentDao.findById                     DistributionService.java:48
                               ─→ ClassDao.findById                         :52
                               ─→ DistributionDao.countByAssignmentAndClass :59
                               ─→ DistributionDao.nextId                    :62
                               ─→ DistributionDao.insert                    :63
                               ─→ AppClock.now                              :63

AssignmentDao · ClassDao · DistributionDao ─→ JdbcTemplate (생성자 주입)   AssignmentDao.java:17-19, ClassDao.java:16-18, DistributionDao.java:19-21
```

- 컨트롤러가 서비스뿐 아니라 **DAO 를 직접 주입받아 호출**한다 (`DistributionController.java:22-30`, `:42-43`, `:53`). 같은 과제 조회(`AssignmentDao.findById`)를 컨트롤러(`:53`)와 서비스(`DistributionService.java:48`)가 한 요청 안에서 두 번 한다.
- `AppClock` 은 스프링 빈이 아니라 static 유틸리티다 (`AppClock.java:6`, `:14`). 주입 없이 컨트롤러 · 서비스가 직접 부른다.
- 트랜잭션: `distribute` 에만 `@Transactional` (`DistributionService.java:46`). 컨트롤러의 과제 조회 · 마감 검사(`DistributionController.java:53-65`)는 트랜잭션 밖이다.
- 템플릿 → 모델 값: `assignments`(`AssignmentRow` 목록), `classes`(`ClassRow` 목록), `now`(`LocalDateTime`), `menu`(`"new"`) (`DistributionController.java:42-45`). flash 값 `message` · `error` 는 `layout.html:29-30` 에서 보인다.

## 4. 공통 코드 · 외부 라이브러리

| 대상 | 역할 | 근거 |
|---|---|---|
| `AppClock` | 현재 시각. **기본은 `2026-09-15 00:00:00` 고정**, 시스템 속성 `app.clock=system` 일 때만 실제 시각 | `AppClock.java:8-20` |
| `DbErrorAdvice` | `DataAccessException` → `db_error.html`. `System.out` 으로 로그, 원인 메시지를 화면에 노출 | `DbErrorAdvice.java:11-16`, `db_error.html:8` |
| `layout.html` | 공통 head · 메뉴 · flash · 꼬리말 조각 | `layout.html:3`, `:23`, `:28`, `:35` |
| MariaDB JDBC 드라이버 | `org.mariadb.jdbc:mariadb-java-client` | `build.gradle:28` |

- `app.clock` 은 `Dockerfile:11`(ENTRYPOINT) · `docker-compose.yml` `assignment` 서비스(`:50-66`) 어디에서도 설정하지 않는다(저장소 전체 grep 결과 `AppClock.java:15` 1건). 따라서 compose 로 띄우면 **고정 시각으로 동작**한다.

## 5. 현행 규칙(CLAUDE.md)과 다른 점 — 이관 시 참고

| 항목 | 레거시 | 근거 |
|---|---|---|
| Controller 가 DAO(Repository) 직접 사용 | 있음 | `DistributionController.java:4-5`, `:42-43`, `:53` |
| Controller 안 `try`/`catch` | 있음 | `DistributionController.java:66-75` |
| `System.out` 로그 | 있음 | `DbErrorAdvice.java:13` |
| 인자 없는 `now()` · 주입 안 된 시계 | 있음 | `AppClock.java:17` |
| 테스트 `@DisplayName` · `@ActiveProfiles` | 없음 | `AppClockTest.java:8-19` |
