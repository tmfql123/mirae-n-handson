# assignment-thymeleaf 데이터 흐름 — '새 배포' 화면 (ERD · 읽기/쓰기 위치)

> 대상: `legacy/assignment-thymeleaf/` · 범위: '새 배포' 화면(`GET /distributions/new`, `POST /distributions`)이 부르는 SQL 만. 선행 문서: [ARCHITECTURE.md](ARCHITECTURE.md)
> 근거 파일: 스키마 `db/mariadb/init/01-schema.sql`(1–20줄, 70–115줄), 시드 `db/mariadb/init/02-seed.sql`(108–145줄), 계정 `db/mariadb/init/03-users.sql`(전문), DAO 3개 전문.
> SQL 은 DAO 3개를 전문으로 읽고, 범위 안 메서드(`AssignmentDao.findAllForSelect` · `findById`, `ClassDao.findAll` · `findById`, `DistributionDao.countByAssignmentAndClass` · `nextId` · `insert`)만 표에 올렸다. 실행한 것은 없다.

## 1. 테이블 (범위 안)

모두 MariaDB `itembank` 에 있다. 뷰 · 프로시저는 쓰지 않는다.

| 테이블 | 주요 컬럼 | 근거 |
|---|---|---|
| `class` (학급) | `id` INT PK, `name` VARCHAR(50), `teacher_id` VARCHAR(20) | `01-schema.sql:75-80` |
| `assignment` (과제) | `id` INT PK, `title` VARCHAR(200), `unit_id` INT **NOT NULL** FK→`unit.id`, `due_at` DATETIME **NOT NULL**, `status` CHAR(1) 기본 `'O'` (주석 `O=진행 C=마감`) | `01-schema.sql:82-90` |
| `distribution` (배포) | `id` INT PK(AUTO_INCREMENT 아님), `assignment_id` INT FK→`assignment.id`, `class_id` INT FK→`class.id`, `distributed_at` DATETIME, `redistributed` TINYINT 기본 0. 인덱스 `idx_distribution_assignment`, `idx_distribution_class` | `01-schema.sql:92-103` |
| `unit` (단원, 문항 은행 소유) | `id` INT PK, `code` VARCHAR(16) UNIQUE, `name` VARCHAR(100) | `01-schema.sql:11-18` |

- `distribution` 에는 `(assignment_id, class_id)` **UNIQUE 제약이 없다** (`01-schema.sql:92-103` 에 PRIMARY KEY · KEY 2개 · FK 2개만). DAO 주석도 "UNIQUE 제약은 없음, 여기서 막는다"고 적는다 (`DistributionDao.java:46`).
- `submission`(제출)은 이 화면이 읽지도 쓰지도 않는다. `DistributionDao` 의 `BASE_SELECT`(`:26`) · `findSubmissions`(`:69-73`)에 나오지만 둘 다 범위 밖 메서드(배포 목록 · 재배포)에서만 쓴다.

## 2. 관계

```
unit 1 ──< assignment          fk_assignment_unit        01-schema.sql:89
assignment 1 ──< distribution  fk_distribution_assignment 01-schema.sql:101
class 1 ──< distribution       fk_distribution_class     01-schema.sql:102
```

- `(assignment, class)` 쌍당 배포 건수는 스키마상 0..N 이다. 1건으로 막는 것은 애플리케이션 검사뿐이다(BUSINESS-RULES BR-07).

## 3. 읽기 · 쓰기 위치

| 대상 | 종류 | SQL (요지) | 위치 | 호출자 |
|---|---|---|---|---|
| `assignment` + `unit` + `distribution` | 읽기 | `BASE_SELECT ... WHERE a.status IN ('O', 'X', 'C') ORDER BY a.due_at ASC, a.id ASC` | `AssignmentDao.java:21-24`, `:34-38` | `DistributionController.form` `:42` |
| `assignment` + `unit` + `distribution` | 읽기 | `BASE_SELECT + " WHERE a.id = ?"` | `AssignmentDao.java:41-48` | `DistributionController.create` `:53`, `DistributionService.distribute` `:48` |
| `class` | 읽기 | ``SELECT id, name, teacher_id FROM `class` ORDER BY id ASC`` | `ClassDao.java:20-22` | `DistributionController.form` `:43` |
| `class` | 읽기 | ``SELECT id, name, teacher_id FROM `class` WHERE id = ?`` | `ClassDao.java:24-30` | `DistributionService.distribute` `:52` |
| `distribution` | 읽기 | `SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?` | `DistributionDao.java:47-52` | `DistributionService.distribute` `:59` |
| `distribution` | 읽기 | `SELECT COALESCE(MAX(id), 0) FROM distribution` (+1 은 Java 에서) | `DistributionDao.java:54-57` | `DistributionService.distribute` `:62` |
| `distribution` | **쓰기** | `INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | `DistributionDao.java:59-63` | `DistributionService.distribute` `:63` |

- `AssignmentDao.BASE_SELECT` 는 `unit` 을 `LEFT JOIN` 한다(`:24`). `assignment.unit_id` 가 NOT NULL + FK(`01-schema.sql:85`, `:89`)라 결과는 내부 조인과 같다.
- `BASE_SELECT` 의 `class_cnt` 서브쿼리(`AssignmentDao.java:23`)가 `distribution` 을 읽지만, '새 배포' 화면 템플릿은 `classCount` 를 쓰지 않는다(`distribution_form.html:14-18` 에 없음).
- 이 화면의 쓰기는 `distribution` INSERT 1건뿐이다(`DistributionDao.java:60` 의 `jdbc.update(` 가 이 INSERT 다 — `update` 로 grep 하면 먼저 걸린다). `UPDATE` 는 `DistributionDao.java:66` 의 `markRedistributed` 1건이 있으나 재배포(범위 밖) 전용이고, `distribute`(`DistributionService.java:47-68`)는 부르지 않는다. `DELETE` 는 DAO 3개 전문에 없다.
- `distributed_at` 에 들어가는 값은 DB `NOW()` 가 아니라 `AppClock.now()`(기본 `2026-09-15 00:00:00` 고정)다 (`DistributionService.java:63`, `AppClock.java:9`, `:19`).

## 4. 시드 데이터에서 볼 점 (`02-seed.sql`)

| 내용 | 근거 |
|---|---|
| 학급 3개(id 1~3) | `02-seed.sql:111-114` |
| 과제 6개. 상태는 `C` 2건 · `O` 4건 — **`X` · `D` 는 시드에 없다** | `02-seed.sql:119-125` |
| 고정 시각 `2026-09-15 00:00:00` 기준 마감 지난 과제는 1 · 2 · 3번 | `02-seed.sql:120-122` |
| 배포 3번 · 4번이 모두 `(과제 2, 학급 1)` — 같은 쌍이 이미 2건 있다(4번 `redistributed = 1`) | `02-seed.sql:133-134` |

## 5. DB 계정

- 앱은 `app`(itembank 모든 권한)으로 접속한다 (`application.properties:3-4`, `03-users.sql:2`, `:9`). 조회 전용 `readonly` 는 이 모듈에서 쓰지 않는다.
