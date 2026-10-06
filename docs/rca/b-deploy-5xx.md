# RCA: b-deploy-5xx

대상 로그: `incident-logs/b-deploy-5xx/` (원본 수정 없음)

> **작성 범위(이번 회차):** 1절 수집 범위 · 2절 타임라인 · 3절 가설 표까지만 작성했다. 반증 시도(로그 · 코드 대조) · 판정 · 채택과 요약 · 원인 · 수정안 등 이후 절은 다음 단계에서 쓴다. 따라서 이 문서의 어떤 문장도 "원인 확정"이 아니다.

## 요약

(미작성 — 4절 판정 이후 작성)

## 1. 수집 범위

> 이 절은 분석 범위만 정한다. 원인 추정은 3절에서 한다.
> 조사 방법: 파일 전체를 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계만 사용.

### 1.1 파일 개요

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|
| `app.log` | 2,938 (로그 레코드 1,275 + 스택트레이스 줄) | 2026-09-17 09:00:04 ~ 14:39:57 | ISO 8601 `YYYY-MM-DDTHH:MM:SS.mmm+09:00` (줄 맨 앞) | `+09:00` 고정 |
| `nginx-access.log` | 20,365 | 2026-09-17 09:00:00 ~ 14:39:58 | nginx combined `[DD/Mon/YYYY:HH:MM:SS +0900]` (초 단위) | `+0900` 고정 |
| `deploy-history.md` | 39 | 배포 4건 2026-09-01 ~ 2026-09-17 | `YYYY-MM-DD HH:MM:SS` (오프셋 표기 없음) | 문서 3행에 "모두 KST" 명시 |

**KST 변환 규칙**

- 세 파일 모두 이미 KST(UTC+9)라 변환은 필요 없다. 표기만 다르다(`+09:00` vs `+0900`, 밀리초 유무). 대조는 초 단위로 맞춘다.
- `deploy-history.md` 의 "배포 시작 · 완료" 는 파이프라인 기준 시각이고, 실제 프로세스 교체 시각은 `app.log` 의 종료 · 기동 줄(1240 ~ 1264행)로 본다. 두 시각은 뜻이 다르다(배포 완료 13:42:31 vs 앱 기동 완료 13:42:00).
- `nginx-access.log` 에는 `request_time` · `upstream_response_time` 필드가 없다(combined 형식, 따옴표 구분 7필드 고정). 응답 지연은 이 자료로 볼 수 없다.

**호스트 · 출처 별칭** (리포트 전체에서 동일)

| 별칭 | 역할 | 근거 |
|---|---|---|
| `API-1` | 배포 대상 호스트 (`modern/api`, v1.4.2 배포) | `deploy-history.md:3` |
| `API-2` | 대기 호스트, 평소 정지 | `deploy-history.md:3` |
| `교사단말A` | 학급 1 · 2 재배포 요청 단말 (`/teacher` Referer) | `nginx-access.log` 재배포 요청 집계 |
| `교사단말B` | 학급 3 재배포 요청 단말 | 같은 집계 |
| `[kube-probe]` | `GET /` 헬스체크 (UA `kube-probe/1.29`, 867줄) | `nginx-access.log:16866` |

그 밖의 클라이언트 IP 는 모두 `10.20.x.x` 로 가렸다.

### 1.2 이상 신호 집계

**레벨별 (`app.log`)** — `INFO` 1,194 · `WARN` 35 · `ERROR` 46. `ERROR` 46건은 전부 `GlobalExceptionHandler : unhandled exception on …` 이고, 바로 다음 줄 예외도 46건 전부 같다:
`JpaObjectRetrievalFailureException: Entity com.example.assignment.Assignment with identifier value 9 does not exist`

**`ERROR` 46건 분해 (경로 · 스택의 앱 프레임)**

| 구간 | 경로 | 건수 | 스택 앱 프레임 | 첫 줄 |
|---|---|---|---|---|
| 배포 전 | `POST /api/distributions/41/redistribute` | 6 | `DistributionController.redistribute:38` → `DistributionService.redistribute:51` → `loadOrThrow:65` | `app.log:194` (09:52:17) |
| 배포 전 | `GET /api/distributions/41` | 1 | `DistributionController.getDistribution:26` → `DistributionService.getDistribution:32` → `loadOrThrow:65` | `app.log:451` (10:31:05) |
| 재기동 후 | `POST /api/distributions/42/redistribute` | 19 | `DistributionController.redistribute:40` → `DistributionService.listByClass:38` | `app.log:1275` (13:46:38) |
| 재기동 후 | `POST /api/distributions/43/redistribute` | 20 | 같음 (`Controller:40` → `listByClass:38`) | (13:50:12 부터) |

- 스택 프레임 집계: `DistributionService.java:38)` 39회 = 재기동 후 `ERROR` 39건과 정확히 일치. `Controller:38` 6 · `Controller:26` 1 = 배포 전 7건과 일치.
- 재기동 후 `ERROR` 39건은 모두 **같은 요청 스레드의 `redistributed distribution 4x … class 1` INFO 줄 직후 수 ms 안에** 찍혔다(예: `app.log:1274` .107 → `app.log:1275` .113, `app.log:2886` .433 → `app.log:2887` .439). 재기동 후 `redistributed distribution 42|43` INFO 는 39줄로 `ERROR` 수와 같다.

**HTTP 상태 (`nginx-access.log`)** — 200 19,278 · 404 969 · 400 29 · 409 35 · 500 46 · 502 8.

- `500` 46건 = `app.log` `ERROR` 46건과 시각 · 경로가 1:1 로 맞는다. 46건 전부 출처가 `교사단말A` 한 대다.
- `502` 8건은 13:41:51 ~ 13:41:59 9초 구간에만 있다(`nginx-access.log:16865` ~ `16872`). 경로는 학생 · 헬스체크 GET 이며 재배포와 무관하다.

**10분 단위 분포 (`nginx-access.log`, 5xx 는 500+502)**

| 구간 | 200 | 404 | 409 | 400 | 5xx | 비고 |
|---|---|---|---|---|---|---|
| 09:00 ~ 09:40 | 494 ~ 637 | 29 ~ 39 | 0 ~ 1 | 0 ~ 2 | 0 | |
| 09:50 | 540 | 40 | 0 | 0 | **3** | 배포 41 재배포 500 |
| 10:00 ~ 11:00 | 529 ~ 644 | 20 ~ 36 | 0 ~ 4 | 0 ~ 1 | 10:30 **1** | 배포 41 GET 500 |
| 11:10 | 622 | 26 | 0 | 0 | **3** | 배포 41 재배포 500 |
| 11:20 ~ 13:30 | 479 ~ 634 | 14 ~ 35 | 0 ~ 4 | 0 ~ 2 | 0 | 기준 구간 |
| **13:40** | 593 | 21 | 0 | 0 | **11** | 502 8 + 500 3 (재기동 · v1.4.2) |
| 13:50 | 513 | 24 | 1 | 0 | **11** | 500 |
| 14:00 ~ 14:30 | 545 ~ 622 | 27 ~ 38 | 0 ~ 2 | 0 ~ 5 | 6 ~ 7 | 500 |

전체 트래픽(200 건수)은 재기동 전후로 같은 범위라, 처리량 저하는 9초 재기동 구간 외에는 보이지 않는다.

**재배포 요청 상태 — 재기동(13:42:00) 전후** (`POST /api/distributions/{id}/redistribute`, 13:41:20 의 43번 요청은 구버전 처리라 "전"에 넣었다)

| 배포 id | 학급 (app INFO 기준) | 재기동 전 | 재기동 후 | 요청 단말 |
|---|---|---|---|---|
| 41 | (로그에 없음 — 미확인) | 500 × 6 | 요청 없음 | 교사단말A |
| 42 | 1 | 200 × 5 | **500 × 19 (200 0)** | 교사단말A |
| 43 | 1 | 200 × 13 | **500 × 20 (200 0)** | 교사단말A |
| 44 · 8 | 2 | 200 × 25 | 200 × 2 | 교사단말A |
| 5 · 6 · 7 | 3 | 200 × 23 | 200 × 4 | 교사단말B |
| 1 · 2 · 3 | (마감 과제) | 409 × 31 | 409 × 4 | 교사단말A |

**기저 소음으로 분류 (이상 판단에서 뺌)**

- `404`: 10분당 14 ~ 40건이 하루 내내 일정. 내용은 없는 문항 id · `/api/unit` · `/robots.txt` · `/apple-touch-icon.png` 등(`app.log:2` ~ `4` 같은 `not found` / `no handler` INFO).
- `409`: `마감된 과제는 재배포할 수 없습니다: distributionId=1|2|3` — 09:06 부터 하루 내내(`app.log:24` 등 WARN 35건 전부). 재기동 전후 빈도 차이 없음.
- `400`: `/api/items/undefined|NaN` 24건이 09:02 부터 10분당 0 ~ 2건 분산(`nginx-access.log:137` 첫 줄). 14:10 구간 5건은 같은 패턴의 소폭 증가로, 재배포 · 5xx 와 경로가 겹치지 않는다.

**이상 구간과 여유 구간**

- 이상 구간 ①: 09:52:17 ~ 11:18:02 — 배포 41 관련 500 7건(배포 전, v1.4.1).
- 이상 구간 ②: 13:41:48 ~ 13:42:00 — 재기동(그레이스풀 종료 → 기동), 502 8건.
- 이상 구간 ③: 13:46:38 ~ 14:34:45(로그 끝 14:39:58 까지 지속 여부 열림) — 학급 1 재배포 500 39건(v1.4.2).
- 기준(평소) 구간: 같은 날 09:00 ~ 13:41(재기동 전). **별도의 평소일 로그는 폴더에 없다**(아래 추가 수집).

### 1.3 추가 수집 대상 (폴더에 없음)

| 자료 | 필요한 이유 |
|---|---|
| 이전 날짜(예: 09-16) `app.log` · `nginx-access.log` | 배포 41 오류가 09-17 이전에도 있었는지(평소일 기준선) |
| 경보 · 모니터링 기록(5xx 알림, 문의 접수 시각) | **첫 감지 시각이 어느 자료에도 없다.** 감지 지연 계산 불가 |
| 배포 파이프라인 실행 로그 | `deploy-history.md` 의 시작 13:40:05 와 앱 종료 13:41:48 사이 1분 43초 동안의 동작 |
| nginx `request_time` · `upstream_response_time`, error.log | 502 원인(업스트림 연결 거부 여부) 확인 |
| DB 조회(`readonly` 계정) | `distribution` id 41 의 `class_id` · `assignment_id`, `assignment` id 9 존재 여부, FK 제약 유무, 배포 41 생성 · 과제 9 삭제 이력 |
| 스테이징 DB 시드에 같은 형태(과제가 없는 배포) 데이터가 있는지 | `deploy-history.md:17` 의 수동 확인 3건이 이 경우를 덮었는지 |
| 다른 기동 프로필 설정(`No active profile set`) 의 배포 전 기동 로그 | 이번 로그엔 기동이 1회뿐이라 v1.4.1 기동 시와 비교 불가 |

**비밀값 노출 (위치만 기록)**

- `deploy-history.md:20` — 배포 명령에 `Authorization` 헤더의 Bearer 토큰이 평문으로 들어 있다.
- `deploy-history.md:7` ~ `10`, `21`, `28`, `34`, `39` — 배포자 · 승인자 이메일(본문에서는 `***@example.com` 으로 표기).
- `app.log` 재배포 사유(`reason=`) 와 `nginx-access.log` 쿼리 `?student=` 에 학생 식별자가 평문으로 남는다(`app.log` 73줄).

## 2. 타임라인

기준 = 같은 날 재기동 전(v1.4.1) 학급 1 재배포의 정상 사이클. 이상 줄의 `[평소: …]` 는 그 기준값이다.

### 2.1 기준 사이클 (v1.4.1, 학급 1 재배포 정상)

| 시각 (KST) | 출처 | 내용 |
|---|---|---|
| 09:01:32 | `app.log:6` | `built class report for class 1: 3 distributions, 10 submissions` — 학급 1 의 배포는 3건 |
| 09:15:53 | `nginx-access.log:931` | `교사단말A` `POST /api/distributions/43/redistribute` → **200** |
| 09:15:53.787 | `app.log:59` | `redistributed distribution 43 (assignment 6, class 1)` — 뒤따르는 `ERROR` 없음 |
| 10:36:16.930 | `app.log:510` | `redistributed distribution 42 (assignment 4, class 1)` — 뒤따르는 `ERROR` 없음 |
| 12:36:36 | `nginx-access.log:12967` · `app.log:1038` | 재기동 전 마지막 배포 42 재배포 → 200 |

### 2.2 이상일 타임라인 (2026-09-17)

| 시각 (KST) | 출처 | 내용 | 평소와 비교 |
|---|---|---|---|
| **09:52:17** | `nginx-access.log:3150` · `app.log:194` | `POST /api/distributions/41/redistribute` → **500**. `Assignment … identifier value 9 does not exist`, 스택 `DistributionService.loadOrThrow:65` | **첫 이상 신호** (첫 알림 이전, 배포 3시간 48분 전) `[평소: 재배포 → 200 또는 마감 409]` |
| 09:52:30 · 09:52:41 | `app.log:232` · `270` | 같은 요청 재시도 2회, 같은 500 | |
| 10:31:05 | `nginx-access.log:5563` · `app.log:451` | `GET /api/distributions/41` → **500**, 같은 예외 (`getDistribution:32` → `loadOrThrow:65`) | 단건 조회도 실패 `[평소: GET 배포 단건 → 200 또는 404 "배포가 없습니다"]` |
| 11:17:40 ~ 11:18:02 | `app.log:655` · `695` · `733` | 배포 41 재배포 500 3회 | 이후 배포 41 요청 없음 |
| 13:38 | `deploy-history.md:21` | v1.4.2 승인 (`***@example.com`) | |
| 13:40:05 | `deploy-history.md:7` | v1.4.2 배포 시작 — "재배포 응답에 학급 배포 이력(history) 포함", `DistributionController.redistribute` 에서 `listByClass(classId)` 호출 추가(`:16`) | |
| 13:41:20.994 | `app.log:1238` · `nginx-access.log:16829` | 구버전에서 배포 43 재배포 → 200 | 재기동 전 마지막 학급 1 성공 |
| 13:41:48.214 | `app.log:1240` | `Commencing graceful shutdown` | |
| 13:41:51 ~ 13:41:59 | `nginx-access.log:16865` ~ `16872` | 학생 · 헬스체크 GET 8건 → **502** | `[평소: 502 0건]` |
| 13:41:55.902 | `app.log:1245` · `1246` | `Starting ItemBankApplication v1.4.2` / `No active profile set, falling back to 1 default profile: "default"` | 배포 전 기동 로그 없음 — 비교 불가 |
| 13:42:00.304 | `app.log:1264` | `Started ItemBankApplication in 4.921 seconds` — 이후 502 없음 | 예상 중단 "10초 안팎"(`deploy-history.md:18`) 범위 |
| 13:42:10 | `nginx-access.log:16884` · `app.log:1266` | `교사단말B` 배포 7(학급 3) 재배포 → 200 | 새 버전에서도 학급 3 정상 |
| 13:42:31 | `deploy-history.md:7` | 파이프라인 "배포 완료" | |
| **13:46:38.107** | `app.log:1274` | `redistributed distribution 42 (assignment 4, class 1)` | 재배포 자체는 처리됨 |
| **13:46:38.113** | `app.log:1275` · `nginx-access.log:17155` | 같은 스레드 6 ms 뒤 `ERROR … /api/distributions/42/redistribute`, 스택 `DistributionController.redistribute:40` → `DistributionService.listByClass:38`, `Assignment … 9 does not exist` → **500** | **v1.4.2 이후 첫 500** `[평소: 같은 요청 → 200, app.log:510]` |
| 13:46:58 · 13:47:08 | `app.log:1313` · `1351` (직전 INFO `1312` · `1350`) | 배포 42 재시도 — 매번 INFO(재배포 기록) 후 500 | 재시도마다 재배포가 다시 기록됨 |
| 13:50:00 | `nginx-access.log:17353` · `app.log:1393` | 배포 8(학급 2) 재배포 → 200 | 새 버전에서 학급 2 정상 |
| 13:50:12 | `app.log:1396` · `nginx-access.log:17365` | 배포 43(학급 1) 첫 500 | `[평소: 200, app.log:59]` |
| 13:56:43 | `nginx-access.log:17734` · `app.log:1754` | 배포 44(학급 2) 재배포 → 200 | |
| 14:21:02 · 14:22:46 | `nginx-access.log:19238` · `19347` | 배포 5(학급 3) → 200 | |
| 14:34:45.433 → .439 | `app.log:2886` → `2887` · `nginx-access.log:20084` | 배포 43 재배포 INFO → 500 (마지막 500) | |
| 14:39:58 | `nginx-access.log:20365` | 로그 끝. 롤백 · 복구 기록 없음 | 롤백 계획만 있음(`deploy-history.md:22`) |

### 2.3 첫 알림보다 앞선 "평소와 다른 줄"

경보 기록이 폴더에 없어 첫 알림 시각은 모른다. 시간순으로 평소와 다른 줄은 다음과 같다.

1. `app.log:194` (09:52:17) — 배포 41 → 과제 9 가 없다는 예외. **v1.4.2 배포 전**이며 하루 중 첫 `ERROR`.
2. `app.log:451` (10:31:05) — 같은 데이터로 단건 조회 `GET` 도 500. 없는 대상이 404 가 아니라 500 으로 응답.
3. `app.log:1246` (13:41:55) — 새 버전이 기본 프로필(`default`)로 기동. 배포 전 기동 줄이 없어 평소와 같은지 판단 불가.
4. `app.log:1274` → `1275` (13:46:38) — 재배포가 기록된 직후 같은 요청이 500. 이후 39회 같은 패턴.

## 3. 가설과 검증

> 이번 회차는 **가설 표까지만** 작성한다. 반증 시도(C1, C2 …) · 코드 대조 · 판정 · 채택은 하지 않았다. "지지 근거"는 로그상 정황일 뿐 검증 결과가 아니다.

### 3.1 가설 표

| ID | 계층 | 한 문장 가설 | 지지 근거 (`파일:줄번호`) | 반증 조건 (이것이 확인되면 틀렸다) | 확인 방법 (명령 · 쿼리) |
|---|---|---|---|---|---|
| H1 | 코드 | v1.4.2 가 재배포 응답에 `listByClass(classId)` 를 추가하면서, 학급 1 의 모든 배포를 과제와 함께 읽게 되었고 그 중 과제가 없는 배포 41 때문에 학급 1 재배포가 전부 500 이 됐다. | `deploy-history.md:15` · `:16`; `app.log:1275` 이하 39건 스택 `Controller.redistribute:40 → DistributionService.listByClass:38`; 재기동 전 42 · 43 은 200(`app.log:59` · `510`), 재기동 후 0/39 성공; 같은 새 버전에서 학급 2 · 3 은 200(`nginx-access.log:16884` · `17353` · `17734` · `19238`) | ① 재기동 전(v1.4.1)에도 42 · 43 재배포가 같은 예외로 실패한 줄이 있다 ② 재기동 후 `ERROR` 중 스택에 `listByClass` 가 없는 학급 1 재배포 500 이 있다 ③ 배포 41 이 학급 1 이 아니다 ④ v1.4.1 로 롤백해도 42 · 43 재배포가 500 이다 | `grep -n 'listByClass' app.log \| wc -l` 과 재기동 후 `ERROR` 수 비교; `awk` 로 재기동 전 42 · 43 상태 재집계; `git log -p` · `git show` 로 `DistributionController.redistribute` · `DistributionService.listByClass` 대조(4절); `readonly` 로 `SELECT id, class_id, assignment_id FROM distribution WHERE class_id = 1;` |
| H2 | DB/스키마 | 배포 41 이 존재하지 않는 과제 9 를 참조하는 고아 행이며(FK 부재 또는 과제 삭제), 이 데이터 결함은 v1.4.2 이전부터 있었다. | `app.log:194` · `451` (09:52, 10:31 — 배포 전 같은 예외); `nginx-access.log:3150` · `5563`; 학급 1 리포트 "3 distributions"(`app.log:6`) — 41 · 42 · 43 으로 **추정**, 41 의 학급은 로그에 직접 없음(**미확인**) | ① `assignment` id 9 가 DB 에 존재한다(→ 매핑 · 조회 조건 문제) ② `distribution` id 41 의 `assignment_id` 가 9 가 아니다 ③ 이전 날짜 로그에서 배포 41 이 정상(200) 응답한 뒤 09-17 오전 사이에 과제 9 삭제 흔적이 없다 | `readonly` 계정: `SELECT id, class_id, assignment_id FROM distribution WHERE id = 41;` · `SELECT id FROM assignment WHERE id = 9;` · `SELECT d.id FROM distribution d LEFT JOIN assignment a ON a.id = d.assignment_id WHERE a.id IS NULL;` · `information_schema.KEY_COLUMN_USAGE` 로 FK 유무 확인; 이전 날짜 로그 수집 |
| H3 | 코드 (방어선) | 재배포가 커밋된 뒤 응답 조립(`listByClass`)에서 실패해, 클라이언트는 500 을 받고 재시도했으며 재시도마다 재배포가 다시 기록됐다(응답 실패 ≠ 처리 실패, 비멱등). 또 대상 데이터 불일치가 404/409 가 아닌 500 으로 매핑됐다. | `app.log:1274` → `1275` (INFO 후 6 ms 뒤 ERROR), `app.log:2886` → `2887`; 재기동 후 `redistributed distribution 42\|43` INFO 39 = 500 39; 배포 42 재기동 후 19회 요청(전 5회, `nginx-access.log` 집계); `app.log:451` GET 도 500 | ① 500 응답 건의 재배포가 실제로는 롤백되어 DB 에 남지 않았다 ② 재배포 이력 테이블에 13:46 ~ 14:34 학급 1 기록이 39건보다 적다 ③ `GlobalExceptionHandler` 가 해당 예외를 이미 404/409 로 매핑하도록 되어 있다(→ 다른 경로 문제) | `readonly`: 재배포 이력(테이블명 4절에서 확인) `WHERE distribution_id IN (42,43) AND 시각 BETWEEN '2026-09-17 13:42' AND '14:40'` 건수; 코드에서 `@Transactional` 경계(Controller 의 두 서비스 호출) · `GlobalExceptionHandler` 매핑 대조(4절) |
| H4 | 운영/설정 | 단일 호스트 무중단 아님 재기동으로 13:41:51 ~ 59 에 502 가 났고, 이는 계획된 중단이며 500 증가와는 별개다. | `app.log:1240` ~ `1264` (종료 13:41:48 → 기동 13:42:00); `nginx-access.log:16865` ~ `16872` (502 8건, 이후 0건); `deploy-history.md:18` "무중단 아님, 예상 중단 10초" | ① 13:42:00 이후에도 502 가 있다 ② 502 중 재기동 구간 밖 줄이 있다 ③ nginx error.log 의 502 사유가 업스트림 연결 거부가 아닌 다른 것이다 | `awk '$9==502' nginx-access.log` 시각 범위 확인; nginx error.log 수집 |
| H5 | 운영/설정 | v1.4.2 가 프로필 없이(`default`) 기동해 운영과 다른 설정(데이터소스 · 매핑 옵션 등)으로 동작했고, 그 차이가 500 을 만들었다. | `app.log:1246` `No active profile set, falling back to 1 default profile` | ① 같은 예외(`Assignment 9 does not exist`)가 배포 전(v1.4.1) 프로세스에서도 났다(`app.log:194`) ② v1.4.1 기동 로그도 `default` 프로필이다 ③ 새 버전에서 학급 2 · 3 재배포 · 일반 조회가 정상이다 | 이전 기동 로그 수집 후 `grep -n 'active profile'`; 배포 매니페스트 · 환경변수(`SPRING_PROFILES_ACTIVE`) 확인 |
| H6 | 측정/집계 | "배포 후 5xx 급증"은 서로 다른 두 현상(재기동 502 8건 + 데이터 500 39건)을 합친 지표이며, 500 은 교사 단말 1대의 재시도가 만든 반복 건수라 사용자 영향 범위는 건수보다 작다. | 500 46건 전부 `교사단말A`; 13:40 구간 5xx 11 = 502 8 + 500 3; 재배포 요청 42번 재기동 전 5회 → 후 19회 | ① 다른 클라이언트(학생 · 다른 교사)에서도 500 이 있다 ② 경보 지표가 502 와 500 을 구분해 집계한다 ③ 재시도가 아닌 서로 다른 교사 · 학급의 요청이다 | `awk '$9>=500{print $1}' nginx-access.log \| sort \| uniq -c`; 경보 규칙 · 대시보드 쿼리 수집 |
| H7 | 운영/설정 | v1.4.2 검증이 시드 데이터(고아 배포 없음) 스테이징에서 재배포 3건 수동 확인으로만 이뤄져, 운영 데이터의 고아 행 조합을 덮지 못했다. | `deploy-history.md:17` "스테이징 DB(시드 데이터)에서 재배포 3건 수동 확인"; `deploy-history.md:14` "수업 중 반영 요청" | ① 스테이징에도 과제가 없는 배포 행이 있고 그 학급으로 재배포를 시험했다 ② 운영 데이터 스냅샷으로 시험한 기록이 있다 | 스테이징 `readonly` 로 H2 의 LEFT JOIN 쿼리 실행; 배포 파이프라인 로그 · 테스트 기록 수집 |

### 3.2 가설 사이의 관계 (판정과 분리)

- **전제 조건(잠재 결함):** H2 — 배포 41 → 과제 9 불일치는 09:52 부터 이미 보였고, 그때는 배포 41 을 직접 건드리는 요청만 실패했다.
- **촉발 경로:** H1 — v1.4.2 의 `listByClass` 호출이 같은 학급(학급 1)의 다른 배포(42 · 43) 재배포에까지 영향 범위를 넓힌 것으로 보인다. H2 가 거짓이면 H1 도 설명력을 잃는다.
- **방어선:** H3(커밋 후 응답 실패 · 500 매핑 · 비멱등 재시도), H7(배포 전 검증) — 둘 다 "왜 막지 못했나 / 왜 피해가 커졌나"에 해당한다.
- **별개 현상:** H4 — 502 8건은 재기동 구간에 한정되며 500 과 경로 · 시각이 겹치지 않는다.
- **측정:** H6 — 지표 해석 문제로, 원인이 아니라 감지 · 영향 평가에 관여한다.
- **대안 설명:** H5 — H1 의 경쟁 가설. 반증 조건 ①은 로그(`app.log:194`)로 바로 시험할 수 있으나 이번 회차에서는 판정하지 않는다.

## 4. 반증 시도 → 판정

(미작성 — 다음 단계)

## 원인

(미작성)

## 근거 로그 줄

(미작성)

## 수정안

(미작성)

## 재발 방지

(미작성)

## 모니터링 항목

(미작성)

## 확인하지 못한 것

(미작성 — 1.3 추가 수집 대상 참고)

---

마스킹 적용: IP 5건(10.20.x.x 0 · 역할 별칭 5 — API-1 · API-2 · 교사단말A · 교사단말B · [kube-probe]) · 이메일 1건 · 학생 식별자 0건(0명, 인용 줄에서 학생 식별자가 든 부분은 생략) · 토큰 1건(값 없이 위치만 기록) · 비밀번호/접속 문자열 0건
