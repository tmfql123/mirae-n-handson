# RCA: c-batch-duplicate

대상 로그: `incident-logs/c-batch-duplicate/` (원본 수정 없음)

## 요약

- **무엇이:** 영업일 2026-09-15 분 `load_datamart` 가 두 번 적재돼 `dm.daily_submission_fact` 가 369,064행(평소 약 18.5만의 2배)이 됐고, `build_report_cache` 가 그 값으로 리포트 캐시를 만들었다.
- **언제:** 2026-09-16 01:44:07 첫 이상 신호(원천 읽기 지연) → 02:58:42 2차 run 시작(중복 시작) → 03:15:31 2배 적재 확정 → 07:00:04 DQ 경보로 감지. 복구 시점은 로그에 없다.
- **원인:** 직접 원인은 `load_datamart` 가 같은 `date` 재실행에 멱등하지 않은 것(`mode=append`). 배경은 1차 run 지연 → SLA 경보 → `force=true` 재실행이 실행 중인 run 을 멈추지 않고 lock 만 우회한 것.
- **확신 수준: 중간.** 두 독립 출처(CSV · DQ)가 정확히 `2 × 184,532` 를 보고했지만, 적재 코드와 테이블 DDL 은 저장소에 없어 직접 보지 못했다.

## 1. 수집 범위

> 이 절은 분석 범위만 정한다. 원인 추정은 다음 절에서 한다.
> 조사 방법: 파일 전체를 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계만 사용.

### 1.1 파일 개요

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|
| `batch-job.log` | 115 | 2026-09-09 01:30:02 ~ 2026-09-16 07:00:04 | `YYYY-MM-DD HH:MM:SS.mmm +0900` (줄 맨 앞, 115줄 전부 이 형식) | `+0900` 고정 (115/115) |
| `datamart-counts.csv` | 25 (헤더 1 + 데이터 24) | `loaded_at` 2026-09-09T01:40:03 ~ 2026-09-16T03:33:42<br>`date` 2026-09-08 ~ 2026-09-15 (8일 × 잡 3개) | `loaded_at` 열: ISO 8601 `YYYY-MM-DDTHH:MM:SS+09:00` (초 단위, 밀리초 없음) | `+09:00` 고정 (24/24) |

**KST 변환 규칙**

- 두 파일 모두 이미 KST(UTC+9)라 시각 변환은 필요 없다. 표기만 다르다(`+0900` vs `+09:00`, 공백 구분 vs `T` 구분, 밀리초 유무). 대조할 때는 초 단위로 맞춘다.
- 주의: 로그의 `date=` 필드와 CSV 의 `date` 열은 **처리 대상 영업일(D)** 이고, 실제 실행 시각은 **D+1 새벽**이다. 예: `date=2026-09-15` 건은 2026-09-16 01:30 이후에 실행된다. 시각 기준 대조는 반드시 줄 맨 앞 타임스탬프 / `loaded_at` 으로 한다.
- 로그의 출처 호스트는 4종이다: `batch-runner@10.20.x.x`(111줄), `batchctl@10.20.x.x`(2), `sla-monitor@10.20.x.x`(1), `dq-check@10.20.x.x`(1). 모두 같은 `+0900` 표기라 호스트 간 시계 차이는 이 로그만으로는 확인할 수 없다.

### 1.2 이상 신호 집계

**키워드별 해당 줄 수 (`batch-job.log`)**

| 분류 | 패턴 | 건수 | 비고 |
|---|---|---|---|
| 오류 | `ERROR`, `FAIL`, `exception`, `abort`, `killed` | 0 | 레벨은 `INFO` 99 · `WARN` 16 두 종류뿐 |
| 경고 | ` WARN ` | 16 | 아래 분 단위 표 |
| 5xx | `\b5[0-9]{2}\b` | 12 → **실제 0** | 12건 모두 타임스탬프 밀리초(`.520` 등). HTTP 응답은 98행 `201 Created` 1건뿐 |
| 타임아웃 | `timeout`, `timed out` | 0 | 대신 `status=NOT_FINISHED`(SLA 미완료) 1건, `elapsed ... exceeds p95` 1건이 지연 신호 |

`datamart-counts.csv` 에는 레벨 · 오류 필드가 없어, 적재 건수(`row_count`)와 적재 시각(`loaded_at`)의 편차를 이상 신호로 본다.

**평소 수준 (2026-09-09 ~ 09-15, 7일)**

- 하루 12줄, 모두 01:30:02 ~ 03:34:08 사이.
- `WARN` 은 매일 정확히 1건: 02:00 의 `load_datamart step=validate skipped=1 student_id=학생A reason="class_id is NULL"`. 7일 내내 같은 학생 · 같은 사유라 **기저 소음**으로 분류하고 이상 판단에서 뺀다.
- 정상 일정: `extract_submissions` 01:30 시작 → 01:40~01:42 SUCCESS / `load_datamart` 02:00 시작 → 02:13~02:16 SUCCESS (elapsed 803~977s) / `build_report_cache` 03:30 시작 → 03:33~03:34 SUCCESS.
- CSV `load_datamart.row_count` 182,615 ~ 186,319, `loaded_at` 02:13 ~ 02:16.

**이상일 (2026-09-16) 분 단위 집계 — 기저 소음 제외 `WARN` + 기타 이상 줄**

| 시각 (KST) | 건수 | 출처 · 행 | 내용 (요약, 해석 없음) |
|---|---|---|---|
| 01:44 | 1 | batch-runner, 86행 | `extract_submissions` RUNNING 중 `source read slow ... replica lag 412s` |
| 01:57 | (0, 지연) | batch-runner | `extract_submissions` SUCCESS, elapsed 1668s (평소 601~736s) |
| 02:00 | 0 | — | 기저 소음 1건만 (학생A) |
| 02:40 | 1 | batch-runner, 94행 | `load_datamart` RUNNING `elapsed 40m exceeds p95 17m` |
| 02:45 | 1 | sla-monitor, 95행 | `load_datamart` `status=NOT_FINISHED sla=02:45` |
| 02:58 | 1 (+INFO 2) | batchctl 97~98행, batch-runner 99행 | 수동 재실행 요청(`force=true`) INFO 2건, 새 run 의 `lock=held_by ... action=ignored` WARN 1건. 같은 분의 학생A 기저 소음 1건(102행)은 제외 |
| 03:12 | (0, 지연) | batch-runner | 첫 run(`ldm-...-0200-edf5`) SUCCESS, elapsed 4357s |
| 03:15 | 1 | batch-runner, 111행 | 두 번째 run SUCCESS 직후 `lock=release_skipped reason="not owner (bypassed)"` |
| 03:30 | 1 | batch-runner, 113행 | `build_report_cache` `input_rows differs from extract_submissions` |
| 07:00 | 1 | dq-check, 115행 | `dm.daily_submission_fact rows=369064 avg_7d=184617 deviation=+99%` |

- 하루 줄 수: 평소 12줄 → 09-16 31줄. 02:58 한 분에 7줄이 몰린다(평소 최대 3줄/분).
- CSV: 24행 중 1행만 벗어난다 — `2026-09-15,load_datamart,369064,2026-09-16T03:15:31+09:00` (건수 평소의 약 2배, 적재 시각 평소보다 약 1시간 늦음). 나머지 23행은 평소 범위.

**제안 이상 구간**

- 시작: **2026-09-16 01:44:07 KST** — 기저 소음이 아닌 첫 `WARN`(86행). 평소엔 이 시각에 `extract_submissions` 가 이미 끝나 있다.
- 끝: **2026-09-16 07:00:04 KST** — 마지막 이상 줄(115행, dq-check). 이 로그의 마지막 줄이기도 해서, 실제 영향이 이후에도 이어지는지는 이 파일로 판단할 수 없다.
- 배치 실행 자체의 이상 구간만 보면 01:44 ~ 03:33(마지막 잡 종료)이고, 03:33 ~ 07:00 은 로그 공백(배치 없음) 뒤 검출 신호 1건이다.

### 1.3 여유 구간 제안

| 구분 | 제안 범위 (KST) | 이유 |
|---|---|---|
| 앞쪽 (넉넉히) | **2026-09-15 01:30 부터** (이상 시작 약 24시간 전) | 직전 정상 사이클(09-15 01:30~03:34) 전체를 비교 기준으로 포함. 최소한으로 줄여도 **2026-09-16 01:00** (첫 잡 시작 30분 전)까지는 둔다 — 01:44 신호가 01:30 시작 직후의 원천 DB 상태와 이어지는지 보려면 시작 이전이 필요하다. |
| 뒤쪽 | **2026-09-16 07:30 까지** (마지막 신호 +30분) | 07:00 DQ 알림 이후 대응 기록을 받기 위함. 이 로그는 07:00:04 에서 끝나므로, 다른 출처를 모을 때 같은 끝 시각을 적용한다. |

최종 수집 범위 제안: **2026-09-15 01:30 ~ 2026-09-16 07:30 KST** (핵심 구간 2026-09-16 01:44 ~ 07:00).

### 1.4 범위 확정 시 메모

- 이 폴더에는 위 두 파일만 있다. 핵심 구간에 등장하는 다른 시스템(원천 `grade-db 10.20.x.x`, `batchctl@10.20.x.x`, `sla-monitor`/`dq-check@10.20.x.x`)의 로그 · 지표는 없으므로, 같은 시간 범위로 추가 수집이 필요한지 다음 단계에서 정한다.
- 보안: `batch-job.log` 98행에 `Authorization: Bearer` 토큰이 평문으로 남아 있다. 이 문서에는 옮기지 않았다. 원본은 수정하지 않았으며, 토큰 폐기 · 로그 마스킹은 별도 조치가 필요하다.

## 2. 타임라인

> 대상 구간: **2026-09-15 01:30 ~ 2026-09-16 07:30 KST** (1절 제안 범위). 해당 줄: `batch-job.log` 73~115행(43줄), `datamart-counts.csv` 20~25행(6행). 07:00:04 이후 07:30 까지는 두 파일 모두 기록이 없다.
> 두 파일 모두 `+09:00` 이라 시각은 변환 없이 썼다. 로그 출처 약어: `BR` = `batch-runner@10.20.x.x`. 로그의 `date=` 는 영업일이고 실행일은 그 다음 날이다.
> 원문 발췌는 원문에서 잘라낸 부분이며 생략은 `...` 으로 표시했다. 98행의 Authorization 헤더 값은 `[TOKEN]` 으로 가렸다.
> **(선후 불확실)** 표시 기준: ① CSV `loaded_at` 은 초 단위라 같은 초의 로그 줄과 선후를 가릴 수 없다. ② 서로 다른 호스트 사이의 2초 이내 차이는 시계 동기 여부를 이 로그로 확인할 수 없어 선후를 단정하지 않는다.
> 원인 해석은 쓰지 않았다. `[평소: …]` 는 1절의 평소 수준(09-09~09-15)과 비교한 사실만 적은 것이다.

### 2.1 기준 사이클 — 2026-09-15 (영업일 09-14, 평소 수준)

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 09-15 01:30:02.644 | batch-job.log:73 | `extract_submissions` 시작 (cron, lock 획득, pid=45649) | `job=extract_submissions date=2026-09-14 ... status=START rows=0 trigger=cron` |
| 09-15 01:42:19.185 | batch-job.log:74 | `extract_submissions` 성공, 42,094행, 736s | `status=SUCCESS rows=42094 elapsed=736s` |
| 09-15 01:42:19 | datamart-counts.csv:20 | extract 건수 42,094 기록 **(선후 불확실: log:74 와 같은 초)** | `2026-09-14,extract_submissions,42094,2026-09-15T01:42:19+09:00` |
| 09-15 02:00:03.546 | batch-job.log:75 | `load_datamart` 시작 run `…-ae84` (cron, lock 획득, pid=47144) | `run_id=ldm-20260915-0200-ae84 status=START rows=0 trigger=cron` |
| 09-15 02:00:04.746 | batch-job.log:76 | read 단계, `mode=append`, 대상 `dm.daily_submission_fact` | `step=read source=extract_submissions/2026-09-14 mode=append` |
| 09-15 02:00:05.946 | batch-job.log:77 | validate 에서 학생A 1건 건너뜀. **첫 발생 09-15 02:00:05, 이후 2건(09-16 02:00:06 · 02:58:44, 90 · 102행), 분당 최대 1건** | `step=validate skipped=1 student_id=학생A reason="class_id is NULL"` |
| 09-15 02:03:54 ~ 02:11:35 | batch-job.log:78~80 | 진행 25% → 50% → 75%. **첫 발생 02:03:54, 이후 2건(약 3분 50초 간격)** | `status=RUNNING progress=25% rows=46514` |
| 09-15 02:15:26.746 | batch-job.log:81 | `load_datamart` 성공, 186,058행, 923s | `status=SUCCESS rows=186058 elapsed=923s` |
| 09-15 02:15:26.786 | batch-job.log:82 | lock 해제 | `lock=released lock_path=/var/lock/batch/load_datamart.lock` |
| 09-15 02:15:26 | datamart-counts.csv:21 | load_datamart 건수 186,058 기록 **(선후 불확실: log:81 · 82 와 같은 초)** | `2026-09-14,load_datamart,186058,2026-09-15T02:15:26+09:00` |
| 09-15 03:30:01.344 | batch-job.log:83 | `build_report_cache` 시작 (cron, lock 획득) | `job=build_report_cache date=2026-09-14 ... status=START` |
| 09-15 03:33:10.566 | batch-job.log:84 | `build_report_cache` 성공, 1,288행, input 186,058 | `status=SUCCESS rows=1288 input_rows=186058` |
| 09-15 03:33:10 | datamart-counts.csv:22 | build_report_cache 건수 1,288 기록 **(선후 불확실: log:84 와 같은 초)** | `2026-09-14,build_report_cache,1288,2026-09-15T03:33:10+09:00` |

09-15 03:33:10 ~ 09-16 01:30:02 사이에는 두 파일 모두 기록이 없다.

### 2.2 이상일 — 2026-09-16 (영업일 09-15)

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 09-16 01:30:02.724 | batch-job.log:85 | `extract_submissions` 시작 run `…-526f` (cron, lock 획득, pid=45704) | `run_id=ext-20260916-0130-526f status=START rows=0 trigger=cron` |
| 09-16 01:44:07.724 | batch-job.log:86 | **WARN** extract 진행 중 원천 읽기 지연 경고, 19,204행 시점. [평소: 이 시각이면 extract 가 이미 SUCCESS (01:40~01:42)] | `rows=19204 note="source read slow: grade-db 10.20.x.x replica lag 412s"` |
| 09-16 01:57:50.724 | batch-job.log:87 | `extract_submissions` 성공, 41,573행, 1668s. [평소: 601~736s] | `status=SUCCESS rows=41573 elapsed=1668s` |
| 09-16 01:57:50 | datamart-counts.csv:23 | extract 건수 41,573 기록 **(선후 불확실: log:87 과 같은 초)** | `2026-09-15,extract_submissions,41573,2026-09-16T01:57:50+09:00` |
| 09-16 02:00:03.643 | batch-job.log:88 | `load_datamart` **1차 run** `…-edf5` 시작 (cron, lock 획득, pid=46735) | `run_id=ldm-20260916-0200-edf5 status=START rows=0 trigger=cron` |
| 09-16 02:00:04.843 | batch-job.log:89 | 1차 run read 단계, `mode=append` | `step=read source=extract_submissions/2026-09-15 mode=append` |
| 09-16 02:00:06.043 | batch-job.log:90 | 학생A validate 건너뜀 (2.1 의 77행과 같은 메시지, 묶음 참조) | `step=validate skipped=1 student_id=학생A` |
| 09-16 02:09:39 ~ 02:47:15 | batch-job.log:91 · 92 · 93 · 96 | 1차 run 진행 25% → 38% → 51% → 64%. **첫 발생 02:09:39, 이후 3건(약 12~13분 간격)**. [평소: 25% 는 시작 후 약 3분 50초, 이후 25%p 단위 · 약 4분 간격] | `run_id=ldm-20260916-0200-edf5 status=RUNNING progress=25% rows=46133` |
| 09-16 02:40:03.943 | batch-job.log:94 | **WARN** 1차 run 경과 40분, p95 17분 초과 | `status=RUNNING note="elapsed 40m exceeds p95 17m" rows=94111` |
| 09-16 02:45:00.512 | batch-job.log:95 (`sla-monitor@10.20.x.x`) | **WARN** SLA 02:45 미완료, data-oncall 통보 | `status=NOT_FINISHED sla=02:45 last_status=RUNNING` |
| 09-16 02:58:41.077 | batch-job.log:97 (`batchctl@10.20.x.x`) | 운영자 재실행 요청, `force=true` | `rerun requested job=load_datamart date=2026-09-15 ... force=true` |
| 09-16 02:58:41.287 | batch-job.log:98 (`batchctl@10.20.x.x`) | 배치 API 에 실행 요청 POST, 201 응답 | `POST http://[batch-runner]:8793/api/v1/jobs/load_datamart/runs ... -> 201 Created` |
| 09-16 02:58:42.465 | batch-job.log:99 | **WARN** **2차 run** `…-8963`: lock 을 1차 run(pid=46735)이 보유 중이나 `--force` 로 무시. **(선후 불확실: 98행과 약 1.2초 차, 호스트 다름)** | `lock=held_by run_id=ldm-20260916-0200-edf5 pid=46735 ... action=ignored` |
| 09-16 02:58:42.477 | batch-job.log:100 | 2차 run 시작 (`trigger=manual`, `lock=bypassed`, pid=47948) | `run_id=ldm-20260916-0258-8963 status=START rows=0 trigger=manual` |
| 09-16 02:58:43.765 | batch-job.log:101 | 2차 run read 단계, 1차와 같은 source, `mode=append` | `step=read source=extract_submissions/2026-09-15 mode=append` |
| 09-16 02:58:44.965 | batch-job.log:102 | 학생A validate 건너뜀 (2차 run, 묶음 참조) | `run_id=ldm-20260916-0258-8963 step=validate skipped=1` |
| 09-16 02:58:51 · 03:06:33 | batch-job.log:103 · 105 | 1차 run 계속 진행 77% → 90% (2차 run 과 동시 진행). **첫 발생 02:58:51, 이후 1건** | `run_id=ldm-20260916-0200-edf5 status=RUNNING progress=77% rows=142089` |
| 09-16 03:02:48 ~ 03:11:18 | batch-job.log:104 · 106 · 107 | 2차 run 진행 25% → 50% → 75%. **첫 발생 03:02:48, 이후 2건(약 4분 간격)** | `run_id=ldm-20260916-0258-8963 status=RUNNING progress=25% rows=46133` |
| 09-16 03:12:41.043 | batch-job.log:108 | 1차 run 성공, 184,532행, 4357s. [평소: 803~977s] | `run_id=ldm-20260916-0200-edf5 status=SUCCESS rows=184532 elapsed=4357s` |
| 09-16 03:12:41.081 | batch-job.log:109 | 1차 run lock 해제 | `lock=released lock_path=/var/lock/batch/load_datamart.lock` |
| 09-16 03:15:31.365 | batch-job.log:110 | 2차 run 성공, 184,532행, 1008s | `run_id=ldm-20260916-0258-8963 status=SUCCESS rows=184532 elapsed=1008s` |
| 09-16 03:15:31.400 | batch-job.log:111 | **WARN** 2차 run lock 해제 건너뜀 | `lock=release_skipped reason="not owner (bypassed)"` |
| 09-16 03:15:31 | datamart-counts.csv:24 | load_datamart 건수 **369,064** 기록. [평소: 182,615~186,319, 적재 02:13~02:16] **(선후 불확실: log:110 · 111 과 같은 초)** | `2026-09-15,load_datamart,369064,2026-09-16T03:15:31+09:00` |
| 09-16 03:30:01.233 | batch-job.log:112 | `build_report_cache` 시작 (cron, lock 획득) | `run_id=rpc-20260916-0330-942d status=START rows=0 trigger=cron` |
| 09-16 03:30:04.333 | batch-job.log:113 | **WARN** input 369,064행이 extract 행수 기준과 다르다는 경고 | `input_rows=369064 note="input_rows differs from extract_submissions` |
| 09-16 03:33:42.433 | batch-job.log:114 | `build_report_cache` 성공, 1,271행, input 369,064 | `status=SUCCESS rows=1271 input_rows=369064` |
| 09-16 03:33:42 | datamart-counts.csv:25 | build_report_cache 건수 1,271 기록 **(선후 불확실: log:114 와 같은 초)** | `2026-09-15,build_report_cache,1271,2026-09-16T03:33:42+09:00` |
| 09-16 07:00:04.610 | batch-job.log:115 (`dq-check@10.20.x.x`) | **WARN** `dm.daily_submission_fact` 행수 7일 평균 대비 +99%, data-oncall 통보 | `rows=369064 avg_7d=184617 deviation=+99% notify=***@example.com` |

09-16 03:33:42 ~ 07:00:04 사이와 07:00:04 ~ 07:30 사이에는 두 파일 모두 기록이 없다.

### 2.3 첫 알림보다 앞선 평소와 다른 줄

범위 안에 `ERROR` 는 없고 `WARN` 이 가장 많은 메시지는 학생A validate(기저 소음)다. 그래서 첫 이상 알림(02:40 p95 초과 경고, 02:45 SLA 미완료)을 기준으로 그 앞의 줄을 다시 확인했다.

| 시각 | 출처 | 평소와 다른 점 |
|---|---|---|
| 01:44:07 | batch-job.log:86 | 원천 읽기 지연 경고(느린 읽기 · replica lag 412s). 평소 같은 시각에는 extract 가 끝나 있다 |
| 01:57:50 | batch-job.log:87 | extract 소요 1668s, 평소(601~736s)의 2배 이상 |
| 02:09:39 | batch-job.log:91 | load 25% 도달이 시작 후 9분 36초, 평소 약 3분 50초 |
| 02:21:21 · 02:33:57 | batch-job.log:92 · 93 | 진행률 단계가 38% · 51% 로, 평소의 25%p 단위와 다르다. 간격도 약 12분으로 평소(약 4분)와 다르다 |

- **설정 재적재 · 트래픽 변화:** 범위 안에서 이런 내용을 담은 줄을 찾지 못했다. `grep -niE 'reload|config|traffic|qps|rps'` 결과 0건이다.
- **추출 행수:** 09-16 extract 행수(41,573)와 load 각 run 행수(184,532)는 평소 범위 안이다.

## 3. 가설과 검증

> 이 절은 가설과 그 검증 방법을 적고, 5 · 6열에 **로그로 확인할 수 있는 항목만** 실행한 결과와 판정을 기록한다. 판정은 이 폴더의 로그 · CSV(와 비교용 `pipeline-samples/`)만 근거로 한 것이며, 코드 · DB · 지표를 본 뒤 바뀔 수 있다. 표의 순서는 계층 나열 순이며 우선순위가 아니다.
> 5열의 `C1`~`C15` 는 3.4절 실행 기록의 명령 번호다.
> 근거 표기: `batch-job.log:NN` · `datamart-counts.csv:NN` 은 원본 로그 줄번호(2절 타임라인의 "출처" 열과 같다), 괄호 안 `md:NNN` 은 이 문서 2절 타임라인의 줄번호다.
> DB 조회는 모두 `readonly` 계정으로 한다(`app` 계정 사용 금지). 98행 인증 토큰은 옮기지 않는다.

### 3.1 증상과 근거 축 정리

- **증상으로만 다루는 것 (원인이라고 부르지 않는다)**
  - 가장 많이 나온 `WARN` 은 학생A validate 건너뜀(`batch-job.log:90` · `:102`)이다. 7일 내내 같은 메시지인 기저 소음이며, 이번 건수 이상과의 관계는 어느 가설에서도 전제하지 않는다.
  - 알림 메시지 3종 — p95 초과(`batch-job.log:94`), SLA 미완료(`:95`), DQ 행수 +99%(`:115`) — 은 "느렸다" · "많다"는 관측 결과일 뿐 원인이 아니다.
- **가설들이 공통으로 쓰는 사실 (해석 없음)**
  - `369,064 = 2 × 184,532`. 1차 run(`batch-job.log:108`)과 2차 run(`:110`)의 `rows` 가 각각 184,532이고, CSV 적재 건수(`datamart-counts.csv:24`)와 DQ 행수(`batch-job.log:115`)가 정확히 그 2배다. (md:131 · 133 · 135 · 140)
  - 두 run 의 25% 시점 행수가 `rows=46133` 으로 같다(`batch-job.log:91` · `:104`, md:120 · 130).
  - 두 run 의 read 단계는 같은 source, 같은 `mode=append`, 같은 target 이다(`batch-job.log:89` · `:101`, md:118 · 127).
  - CSV 에는 영업일 09-15 의 `load_datamart` 행이 **1개**뿐이고 `loaded_at` 이 2차 run 종료 시각(03:15:31)이다. 1차 run 종료 시각(03:12:41)의 행은 따로 없다(md:131 · 135).

### 3.2 가설 표

| # | 계층 | 1. 가설 (한 문장) | 2. 지지 근거 (파일:줄번호) | 3. 반증 조건 (이것이 확인되면 이 가설은 틀렸다) | 4. 확인 방법 | 5. 로그 확인 결과 (명령 → 숫자) | 6. 판정 · 이유 · 부족한 로그 |
|---|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 (`load_datamart` 적재 로직) | `load_datamart` 는 `mode=append` 로 적재하면서 같은 영업일(`date`) 분을 먼저 지우거나 덮어쓰는 멱등 처리가 없어서, 같은 `date` 로 두 번 실행되면 행이 그대로 누적된다. | `batch-job.log:89` · `:101` (두 run 모두 `mode=append`, 같은 source · target, md:118 · 127) / `:108` · `:110` (두 run 모두 SUCCESS, 각 184,532행, md:131 · 133) / `datamart-counts.csv:24` (369,064 = 2 × 184,532, md:135) | ① 적재 코드에 실행 전 `DELETE ... WHERE date = ?` · 파티션 교체 · `INSERT ... ON DUPLICATE KEY UPDATE`/`MERGE` 같은 영업일 단위 멱등 처리가 있다. ② 또는 실제 테이블에서 영업일 09-15 행 중 1차 run 이 쓴 행이 0건이다(1차 run 쓰기가 롤백 · 덮어쓰기됨). | 저장소에 batch-runner 코드가 없다(`grep -rIl load_datamart` 결과는 로그 · CSV · 템플릿뿐). batch-runner(`10.20.x.x`)의 `load_datamart` 적재 코드에서 `mode=append` 분기와 쓰기 SQL 을 연다. 테이블에 run 식별 컬럼이 있으면 `readonly` 로 `SELECT run_id, COUNT(*) FROM dm.daily_submission_fact WHERE date='2026-09-15' GROUP BY run_id;` — 두 run_id 가 각 184,532건이면 ②는 성립하지 않는다. | C1 `mode=append` read 9/9건 (모든 load run) / C2 삭제 · 덮어쓰기 · 롤백 계열 키워드 **0건** / C3 1차 `rows=184532` · 2차 `rows=184532` 모두 SUCCESS / C4 `184532 × 2 = 369064` / C10 DQ 가 `table=dm.daily_submission_fact rows=369064` 로 테이블 행수를 보고 | **유지** — 로그에는 선삭제 · 롤백 흔적이 0건이고 두 run 의 행수 합이 테이블 행수(C10)와 정확히 같아 반증 조건 ②(1차 run 쓰기 0건)에 맞는 신호가 없다. 단 반증 조건 ①(코드의 멱등 처리 유무)은 로그로 볼 수 없으므로 batch-runner 적재 코드 확인이 남아 있다. |
| H2 | DB · 스키마 (`dm.daily_submission_fact`) | `dm.daily_submission_fact` 에 자연키(영업일 · 학생 · 제출 등) 유니크 제약이 없어서, 같은 행이 두 번 INSERT 되어도 DB 가 거부하지 않는다. | `batch-job.log:110` (2차 run 이 오류 없이 SUCCESS, md:133) / 범위 안 `ERROR` 0건(1.2절 집계) / `datamart-counts.csv:24` · `batch-job.log:115` (테이블 행수가 2배로 관측, md:135 · 140) | ① 테이블에 자연키 `UNIQUE`/`PRIMARY KEY` 가 있다 — 그렇다면 중복 INSERT 는 실패했어야 하므로, 2배 행수는 키가 서로 다른 행(예: run 별 대리키 포함 키)이라는 뜻이 되어 "제약 부재" 가설은 틀린다. ② `COUNT(*)` 와 `COUNT(DISTINCT <자연키>)` 가 영업일 09-15 에서 같다(중복 행 자체가 없다). | `readonly` 계정으로 `SHOW CREATE TABLE dm.daily_submission_fact;` · `SHOW INDEX FROM dm.daily_submission_fact;` 그리고 `SELECT COUNT(*), COUNT(DISTINCT <자연키 컬럼들>) FROM dm.daily_submission_fact WHERE date='2026-09-15';` (dm 스키마가 어느 DB 에 있는지는 이 자료로 확인되지 않음 — 접속 대상부터 확인 필요) | C5 `duplicate`·`unique`·`constraint`·`integrity`·`ERROR` **0건** / C10 테이블 행수 369,064 / C3 두 run 이 같은 source 를 각 184,532행 적재 | **판단 불가(로그 부족)** — 오류 0건과 2배 행수는 "제약 없음"과 "run 별 값이 섞인 키라 제약을 통과"(반증 조건 ①) 두 경우 모두와 맞아서 로그로 가를 수 없다. 필요한 것: 테이블 DDL(`SHOW CREATE TABLE` 결과) 또는 적재 시 DB 가 남긴 경고 · 오류 로그(dm DB 의 general/error log), 행 단위 키를 남기는 적재 로그. |
| H3 | 운영 · 설정 (`batchctl` 재실행 · `--force` 동작) | `batchctl` 의 `force=true` 재실행은 실행 중인 기존 run 을 멈추지 않고 lock 만 우회하도록 되어 있어서, 운영자 재실행이 1차 run 과 **동시에 도는 2차 run** 을 만들었다. | `batch-job.log:97` (`force=true` 재실행 요청, 사유 `"SLA 알림 — 멈춘 것으로 보고 재실행"`, md:123) / `:99` (`lock=held_by ... pid=46735 action=ignored reason="--force"`, md:125) / `:100` (`lock=bypassed`, md:126) / `:103` · `:105` · `:108` (2차 시작 후에도 1차 run 이 77% → 90% → SUCCESS, md:129 · 131) / `:111` (`release_skipped reason="not owner (bypassed)"`, md:134) | ① `--force` 가 문서 · 코드상 "기존 run 종료 후 재시작"으로 정의돼 있고, 02:58 에 pid 46735 에 종료 신호가 실제로 전달된 기록이 있다(그렇다면 동시 실행은 설정이 아니라 종료 실패의 문제다). ② 런북이 force 재실행 전에 실행 중 run 확인을 요구하고 `batchctl` 이 이를 차단하도록 설정돼 있다(그렇다면 설정이 아니라 절차 미준수 문제로 분리된다). | batch-runner 의 lock 처리 코드에서 `--force` 분기(`action=ignored` 를 남기는 곳)를 연다. `batchctl` 설정 · 런북에서 `force` 의 정의와 기본값을 확인한다. batch-runner 호스트의 02:58 전후 프로세스 · 시그널 기록(syslog 등, 이 폴더에 없음)에서 pid 46735 · 47948 을 찾는다. 비교군 확인: `grep -nE 'force\|bypass' pipeline-samples/batch-logs/*.log` — 09-15 영업일 외에는 force 재실행이 없어 "평소의 force 동작"과 대조할 수는 없다. | C6 `kill`·`sigterm`·`signal`·`terminat`·`cancel`·`abort`·`stopp` **0건** / C7 force 요청(02:58:41) 뒤에도 1차 run 이 `progress=77% rows=142089` → `90% rows=166078` → `SUCCESS rows=184532` 3줄 기록 / C14 `force`·`bypass` 는 `pipeline-samples` 10개 파일 중 09-15 영업일 파일에서만 4건, 나머지 9개 파일 0건 | **유지** — force 이후 1차 run 이 종료되지 않고 끝까지 진행해 성공했고 종료 신호 기록은 0건이라, 반증 조건 ①("종료 후 재시작"으로 동작)에 맞는 신호가 없다. 다만 이것이 설계 · 설정인지 절차 미준수인지(반증 조건 ②)는 로그로 가를 수 없고, 비교할 다른 force 사례도 없다(C14). 필요한 것: `batchctl` 감사 로그 · 런북, batch-runner 호스트의 프로세스 · 시그널 기록. |
| H4 | 트래픽 · 인프라 (원천 `grade-db` 지연) | 원천 `grade-db 10.20.x.x` 의 replica lag 가 extract 와 1차 load 를 함께 느리게 만들었고, 그 지연이 SLA 미완료 알림 → force 재실행을 촉발했다. (H1~H3 의 중복을 일으킨 조건이 아니라, 재실행이 일어난 계기에 대한 가설) | `batch-job.log:86` (`source read slow: grade-db 10.20.x.x replica lag 412s`, md:114) / `:87` (extract 1668s, 평소 601~736s, md:115) / `:91`~`:93` (load 25% 까지 9분 36초, 이후 약 12분 간격, md:120) / `:94` · `:95` (p95 초과 · SLA 미완료, md:121 · 122) / `:97` (재실행 사유가 SLA 알림, md:123) | ① `load_datamart` 가 `grade-db` 를 전혀 읽지 않는다 — `:89` 의 source 는 `extract_submissions/2026-09-15` 로, 원천 DB 가 아니라 extract 산출물이다. 이것이 사실이면 replica lag 로는 load 지연을 설명할 수 없고, load 지연은 다른 원인(대상 DB 쓰기 등)이다. ② `:110` 의 2차 run 은 1차 run 과 **동시에** 돌았는데도 1008s(평소 803~977s 근처)에 끝났다 — 02:00~02:58 사이 대상 DB · 배치 호스트 지표가 평소와 같다면 "공통 인프라 지연" 대신 1차 run 고유 문제(예: 1차 run 프로세스 상태)라는 쪽이 된다. ③ `grade-db` replica lag 지표가 02:00 이후 이미 정상이었다. | batch-runner 의 `load_datamart` 설정 · 코드에서 read 대상(파일 · 스테이징 테이블 · 원천 DB 중 무엇인지)을 확인한다. `grade-db 10.20.x.x` 의 replication lag 지표(`SHOW SLAVE STATUS` 의 `Seconds_Behind_Master` 이력 또는 모니터링 대시보드)를 01:30~03:15 범위로 받는다. dm 대상 DB 와 batch-runner 호스트(`10.20.x.x`)의 같은 시간대 CPU · IO · 락 대기 지표를 받는다. (세 자료 모두 이 폴더에 없음 — 1.4절의 추가 수집 대상) | C8 `grade-db` 언급은 86행 **1건**뿐(extract 단계) / C9 load 의 `source=` 는 9건 모두 `extract_submissions/<date>` / C15 처리 속도: 평소(09-15 run) 구간별 **202행/s**, 1차 run 02:00~02:09 **80행/s** → 이후 **30~34행/s** → 02:58 이후 **50~52행/s**, 같은 시간대(02:58~03:15)의 2차 run **179~188행/s** | **판단 불가(로그 부족)** — 로그상 load 는 grade-db 가 아니라 extract 산출물을 읽고(C9), 02:58 이후 같은 호스트 · 같은 대상의 2차 run 은 평소의 약 90% 속도인데 1차 run 만 계속 느렸다(C15) — "공통 인프라 지연"으로는 02:58 이후 1차 run 의 느림이 설명되지 않는다. 그러나 02:00~02:58 구간의 원인과 load 가 내부적으로 grade-db 에 접근하는지는 로그로 알 수 없다. 필요한 것: `grade-db` replica lag 지표(01:30~03:15), dm 대상 DB · batch-runner 호스트의 IO · 락 대기 지표, `load_datamart` 의 단계별(read/write) 소요 시간 로그. |
| H5 | 측정 · 집계 (`row_count` · DQ 행수 산출) | 테이블에는 중복이 없고, 369,064 는 집계 쪽(CSV `row_count` 기록 또는 행수 산출 쿼리)이 두 run 의 적재 건수를 영업일 단위로 합산해 만든 값이다. | `datamart-counts.csv:24` (09-15 `load_datamart` 행이 1개, `loaded_at` 이 2차 run 종료 시각 = run 별이 아니라 (date, job) 단위로 기록, md:135) / `batch-job.log:108` · `:110` (run 별 rows 는 각 184,532, md:131 · 133) | ① 테이블을 직접 세었을 때 영업일 09-15 의 `COUNT(*)` 가 369,064 이고 `COUNT(DISTINCT <자연키>)` 가 184,532 근처다(실제 중복 행이 존재). ② 이미 있는 반대 증거: `batch-job.log:115` 의 DQ 검사는 CSV 와 **다른 출처**(`dq-check@10.20.x.x`)인데 같은 369,064 를 냈다 — 두 측정이 같은 집계 버그를 공유하지 않는 한 이 가설은 유지하기 어렵다. 이 점을 뒤집으려면 DQ 와 CSV 가 같은 집계 뷰 · 쿼리를 쓴다는 것이 확인돼야 한다. | `readonly` 계정으로 `SELECT COUNT(*), COUNT(DISTINCT <자연키 컬럼들>) FROM dm.daily_submission_fact WHERE date='2026-09-15';` (H2 와 같은 쿼리). CSV `row_count` 를 쓰는 코드와 dq-check 의 행수 쿼리를 열어 같은 뷰 · 쿼리를 공유하는지 본다. 후속 상태 대조: `grep -E '^2026-09-1[56],load' pipeline-samples/datamart-counts.csv` (다음 영업일 09-16 은 184,895 로 평소 범위 — 증상이 다음 사이클에 이어지지 않았는지 보는 용도이며, 09-15 분 중복이 테이블에 남아 있는지는 이것으로 알 수 없다). | C10 DQ 줄은 CSV 와 다른 호스트(`dq-check@10.20.x.x`)이며 `table=dm.daily_submission_fact rows=369064` / C11 CSV 의 09-08~09-14 load 건수 평균 **184,617.4** = DQ 의 `avg_7d=184617` / C12 CSV 의 (date, job) 키 24개, 중복 키 0 / C13 run 별 SUCCESS rows 는 09-15 에 184,532 × 2건, CSV 는 369,064 | **기각** — CSV 와 다른 출처인 DQ 검사가 테이블 이름을 명시해 369,064 행을 보고했고, DQ 의 7일 평균이 CSV 값과 일치해(C11) 평소에도 둘이 같은 테이블 행수를 보고 있다. "테이블엔 중복이 없고 집계만 합산했다"는 가설과 맞지 않는다. 남는 단서: DQ 가 테이블을 직접 `COUNT` 하는지는 코드 미확인이며, 이것이 합산 뷰로 밝혀지면 다시 연다. |

### 3.3 가설 사이의 관계 (판정 아님)

- H1 과 H2 는 서로 배타적이지 않다. "적재가 멱등이 아니다"(H1)와 "DB 가 중복을 막지 않는다"(H2)는 각각 따로 참 · 거짓이 될 수 있고, 확인 방법도 다르다(적재 코드 vs `SHOW CREATE TABLE`).
- H3 은 "같은 `date` 로 두 번 실행된 이유"에 대한 가설이고, H4 는 "그 재실행이 왜 요청됐는가"에 대한 가설이다. H1 · H2 와는 묻는 질문이 다르다.
- H5 는 H1 · H2 와 정면으로 충돌한다. H5 가 맞으면 테이블에는 중복이 없으므로 H1 · H2 의 결과(누적 · 미거부)도 관측되지 않아야 한다. 따라서 `COUNT(*)` / `COUNT(DISTINCT <자연키>)` 쿼리 한 번이 H2 · H5 를 함께 가른다.
- 2절에서 확인한 대로 범위 안에 설정 재적재 · 배포 · 트래픽 변화를 담은 줄은 0건이다(md:155). 배포 · 설정 변경 계층 가설은 이 로그만으로는 지지 근거가 없어 세우지 않았고, 배포 이력은 1.4절의 추가 수집 대상으로 남긴다.

### 3.4 로그 확인 실행 기록 (2026-10-06)

> 실행 위치는 저장소 루트, `L=incident-logs/c-batch-duplicate/batch-job.log`, `C=incident-logs/c-batch-duplicate/datamart-counts.csv`. 원본 파일은 읽기만 했다. 출력은 그대로 옮겼다(줄 앞 시각 · 호스트처럼 길어서 자른 부분은 `...`).
> 로그로 확인할 수 없는 항목(batch-runner 코드, `SHOW CREATE TABLE`, DB 행수 쿼리, grade-db · 호스트 지표, 런북)은 실행하지 않았다. 해당 자료가 저장소에 없다.

| # | 대상 | 명령 | 결과 |
|---|---|---|---|
| C1 | H1 | `grep -n 'job=load_datamart' $L \| grep -c 'mode=append'` / `grep -c 'step=read' $L` | `9` / `9` |
| C2 | H1 | `grep -ciE 'delete\|truncate\|overwrite\|replace\|upsert\|merge\|rollback\|dedup' $L` | `0` |
| C3 | H1 · H2 | `grep -n 'date=2026-09-15' $L \| grep 'job=load_datamart' \| grep 'status=SUCCESS' \| grep -oE 'run_id=[^ ]+\|rows=[0-9]+\|elapsed=[0-9]+s' \| paste - - -` | `run_id=ldm-20260916-0200-edf5 rows=184532 elapsed=4357s`<br>`run_id=ldm-20260916-0258-8963 rows=184532 elapsed=1008s` |
| C4 | H1 | `echo $((184532*2))` | `369064` |
| C5 | H2 | `grep -ciE 'duplicate\|unique\|constraint\|integrity\|ERROR' $L` | `0` |
| C6 | H3 | `grep -ciE 'kill\|sigterm\|signal\|terminat\|cancel\|abort\|stopp' $L` | `0` |
| C7 | H3 | `grep -n 'run_id=ldm-20260916-0200-edf5' $L \| awk '$0 ~ /02:5[89]\|03:/' \| grep -oE 'status=[A-Z]+\|progress=[0-9]+%\|rows=[0-9]+'` | `status=RUNNING progress=77% rows=142089` (103행)<br>`status=RUNNING progress=90% rows=166078` (105행)<br>`status=SUCCESS rows=184532` (108행) |
| C8 | H4 | `grep -n 'grade-db\|10.20.x.x' $L` / `grep -c 'grade-db' $L` | `86:2026-09-16 01:44:07.724 +0900 WARN [...` / `1` |
| C9 | H4 | `grep -n 'source=' $L \| grep -oE 'job=[a-z_]+\|source=[^ ]+' \| paste - - \| sort \| uniq -c` | `job=load_datamart source=extract_submissions/2026-09-08` ~ `/2026-09-14` 각 `1`<br>`job=load_datamart source=extract_submissions/2026-09-15` `2` |
| C10 | H1 · H5 | `sed -n 115p $L \| grep -oE '\[[^]]+\]\|table=[^ ]+\|rows=[0-9]+\|avg_7d=[0-9]+\|deviation=[^ ]+'` | `[dq-check@10.20.x.x]` `table=dm.daily_submission_fact` `rows=369064` `avg_7d=184617` `deviation=+99%` |
| C11 | H5 | `awk -F, '$2=="load_datamart" && $1>="2026-09-08" && $1<="2026-09-14"{s+=$3;n++} END{printf "n=%d sum=%d avg=%.1f\n",n,s,s/n}' $C` | `n=7 sum=1292322 avg=184617.4` |
| C12 | H5 | `awk -F, 'NR>1{k[$1","$2]++} END{for(x in k) if(k[x]>1) print x,k[x]; print "keys",length(k)}' $C` | `keys 24` (2회 이상 나온 키 없음) |
| C13 | H5 | `grep -n 'job=load_datamart' $L \| grep 'status=SUCCESS' \| grep -oE 'date=[0-9-]+\|rows=[0-9]+' \| paste - -` | 09-08 `182615` · 09-09 `183212` · 09-10 `185231` · 09-11 `186033` · 09-12 `186319` · 09-13 `182854` · 09-14 `186058` · 09-15 `184532` · 09-15 `184532` |
| C14 | H3 | `grep -cE 'force\|bypass' pipeline-samples/batch-logs/*.log` | `2026-09-15.log:4`, 나머지 9개 파일(09-08~09-14 · 09-16 · 09-17) 각 `0` |
| C15 | H4 | 아래 awk — load run 별로 연속한 `START`/`RUNNING`/`SUCCESS` 줄 사이의 행 증가 ÷ 초 | 아래 표 |
| (참고) | H5 | `grep -E '^2026-09-1[56],load' pipeline-samples/datamart-counts.csv` | `2026-09-15,load_datamart,369064,...03:15:31` / `2026-09-16,load_datamart,184895,...02:15:24` — 다음 영업일은 평소 범위. 09-15 분 중복이 테이블에 남았는지는 이것으로 알 수 없다 |

C15 명령:

```bash
grep -E 'job=load_datamart' $L | grep -E 'ldm-20260915-0200-ae84|ldm-20260916' \
 | grep -E 'status=(START|RUNNING|SUCCESS)' \
 | awk '{split($2,t,":"); s=t[1]*3600+t[2]*60+t[3];
        match($0,/run_id=[^ ]+/); r=substr($0,RSTART+7,RLENGTH-7);
        match($0,/ rows=[0-9]+/); n=substr($0,RSTART+6,RLENGTH-6);
        if(r in ps) printf "%s %s rows=%d %+ds %+d행 = %.0f행/s\n",r,$2,n,s-ps[r],n-pn[r],(n-pn[r])/(s-ps[r]);
        else printf "%s %s rows=%d START\n",r,$2,n; ps[r]=s; pn[r]=n}'
```

C15 결과 (94행 p95 WARN 은 `rows=94111` 이 93행과 같고, 95행 SLA WARN 은 `rows=` 가 없어 awk 가 0 으로 읽었다. 이 두 줄을 빼고 93행 → 96행을 한 구간으로 다시 계산한 값이 아래 표다):

| run | 구간 (KST) | 행 증가 | 초 | 행/s |
|---|---|---|---|---|
| 09-15 `…-ae84` (평소) | 02:00:03 → 02:03:54 → 02:07:45 → 02:11:35 → 02:15:26 | 46,514 / 46,515 / 46,514 / 46,515 | 230 씩 | **202** (4구간 모두) |
| 09-16 1차 `…-edf5` | 02:00:03 → 02:09:39 | 46,133 | 576 | **80** |
| | 02:09:39 → 02:21:21 | 23,989 | 702 | **34** |
| | 02:21:21 → 02:33:57 | 23,989 | 756 | **32** |
| | 02:33:57 → 02:47:15 (94 · 95행 제외) | 23,989 | 798 | **30** |
| | 02:47:15 → 02:58:51 | 23,989 | 696 | **34** |
| | 02:58:51 → 03:06:33 | 23,989 | 462 | **52** |
| | 03:06:33 → 03:12:41 | 18,454 | 367 | **50** |
| 09-16 2차 `…-8963` | 02:58:42 → 03:02:48 → 03:07:00 → 03:11:18 → 03:15:31 | 46,133 씩 | 245 / 252 / 258 / 252 | **188 / 183 / 179 / 182** |

### 3.5 판정

> 대상: 3.2절에서 **유지**된 H1 · H3 을 코드 · 설정과 대조하고, 로그의 실행 ID · 시작 사유와 CSV 를 맞춰 본 뒤 최종 판정한다. 벤치마크 · 테스트 · DB 조회는 하지 않았다. 설정값은 파일에서 읽은 값만 썼다.

#### 3.5.1 코드 · 설정 탐색 결과

로그에 찍힌 단서로 저장소 전체(`node_modules` · `.git` · `build` 제외)를 검색했다.

```bash
grep -rIlE --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
  'load_datamart|daily_submission_fact|batchctl|batch-runner|dq-check|sla-monitor|load_datamart\.lock|/var/lock/batch|8793|api/v1/jobs|extract_submissions|build_report_cache|ldm-|mode=append|lock=bypassed|release_skipped|--force|10\.20\.x\.' .
```

- 걸린 파일은 로그 · CSV(`incident-logs/c-batch-duplicate/*`, `pipeline-samples/batch-logs/*`, `pipeline-samples/datamart-counts.csv`), 이 문서, 그리고 아래 세 파일뿐이다. **batch-runner · batchctl 의 소스, cron 정의, lock 처리 코드, `dm` 스키마 DDL 은 저장소에 없다.**

| 파일:줄 | 내용 (원문) | 이번 판정과의 관계 |
|---|---|---|
| `templates/CLAUDE.data-pipeline.md:56` | `- 모든 job 은 **재실행 가능(idempotent)** 해야 한다. 같은 날짜로 두 번 돌려도 건수가 두 배가 되지 않게 upsert 또는 사전 삭제를 쓴다.` | 팀 규칙(요구 사항)이다. 실제 `load_datamart` 가 이를 지키는지는 보여 주지 않는다. |
| `templates/CLAUDE.data-pipeline.md:70` | `` - 적재 대상 테이블을 `DROP` · `TRUNCATE` 하는 코드를 만들지 않는다. 재적재는 기준일 범위 `DELETE` 후 `INSERT` 로 한다. `` | 같은 성격의 규칙. 구현 증거가 아니다. |
| `templates/CLAUDE.data-pipeline.md:57` | `` 로그 한 줄에 `job=<이름> date=<기준일> run_id=<실행 ID> status=<START\|SUCCESS\|FAIL> rows=<건수>` 를 반드시 포함한다. `` | 실제 로그에는 `status=RUNNING` 이 있고 `FAIL` 은 없다. 템플릿이 이 batch-runner 의 실제 구현을 그대로 반영한 문서라고 볼 근거가 없다는 점만 확인된다. |
| `pipeline-samples/terraform/variables.tf:13~17` | `variable "api_subnet_cidr" { description = "문항 은행 API 서버가 있는 서브넷 CIDR (DB 접속 허용 출발지)" ... default = "10.20.x.x/24" }` | batch-runner(`10.20.x.x`) · batchctl(`10.20.x.x`) 가 같은 /24 대역이라 검색에 걸렸다. 문항 은행 dev DB 보안 그룹(`main.tf:28~38`, 3306) 설정이며 배치 동작과 관계없다. |

- DB 초기화 스크립트(`db/mariadb/init/01-schema.sql`, `db/mssql/init/01-schema.sql`)에도 `daily_submission` 은 없다(`grep -rn 'daily_submission' db/` 0건). H2 의 DDL 확인도 이 저장소로는 불가하다.

#### 3.5.2 로그의 실행 ID · 시작 사유와 CSV 대조

```bash
grep 'status=START' $L | grep -oE 'trigger=[a-z]+' | sort | uniq -c                       # R1
grep -n 'job=load_datamart' $L | grep 'status=START' \
  | grep -oE '^[0-9]+|run_id=[^ ]+|trigger=[^ ]+|lock=[^ ]+|pid=[0-9]+' | paste - - - - -   # R2
grep -oE 'lock=[a-z_]+' $L | sort | uniq -c                                                 # R4
# R5: CSV 의 load_datamart 각 행에 대해, 같은 date 의 load SUCCESS 줄(시각 · run_id · rows)을 붙여 출력
```

| # | 결과 (원문 숫자) |
|---|---|
| R1 | `24 trigger=cron` / `1 trigger=manual` |
| R2 | load START 9건: 09-09 ~ 09-16 `0200` run 8건은 모두 `trigger=cron lock=acquired`. 유일한 예외는 100행 `run_id=ldm-20260916-0258-8963 trigger=manual lock=bypassed pid=47948` |
| R3 | 97행 `rerun requested job=load_datamart date=2026-09-15 reason="SLA 알림 — 멈춘 것으로 보고 재실행" force=true` / 99행 `lock=held_by run_id=ldm-20260916-0200-edf5 pid=46735 since=02:00:03 action=ignored reason="--force"` / 109행 1차 run `lock=released` / 111행 2차 run `lock=release_skipped reason="not owner (bypassed)"` |
| R4 | `24 lock=acquired` · `8 lock=released` · `1 lock=held_by` · `1 lock=bypassed` · `1 lock=release_skipped` |
| R5 | 09-08 ~ 09-14 (7행): CSV `row_count` = 그날 load run 의 `rows`, CSV `loaded_at` = 그 run 의 SUCCESS 시각(초 단위)으로 **7/7 일치**. 09-15: CSV `369064 at=03:15:31` ↔ SUCCESS 2건 `03:12:41 edf5 rows=184532` · `03:15:31 8963 rows=184532` |

R5 에서 읽히는 사실:

- 평소에는 CSV 값과 run 의 `rows` 가 같아서 둘을 구별할 수 없다. 09-15 에는 CSV 가 2차 run SUCCESS 와 **같은 초**에 기록됐는데, 그 run 자신이 보고한 `rows=184532` 가 아니라 `369064` 를 적었다. 즉 CSV `row_count` 는 "이번 run 이 넣은 건수"가 아니라 **적재 직후 영업일 단위 테이블 행수**에 가깝다(DQ 115행과 같은 값).
- 1차 run SUCCESS(03:12:41) 시각의 CSV 행은 없다. CSV 는 (date, job) 단위로 덮어써지는 것으로 보인다(3.4 C12 키 중복 0). 덮어쓰기 로직은 코드가 없어 확인하지 못했다.

#### 3.5.3 가설별 대조 판정

| 가설 | 코드 · 설정 대조 판정 | 근거 |
|---|---|---|
| H1 적재가 영업일 단위로 멱등이 아님 | **관련 코드를 찾지 못했다** | `load_datamart` 구현이 저장소에 없다(3.5.1). `templates/CLAUDE.data-pipeline.md:56` · `:70` 은 "멱등이어야 한다"는 규칙일 뿐 구현이 지켰는지는 알려 주지 않는다. 로그 쪽 대조 결과는 가설과 맞는다: 결과 행수가 정확히 `2 × 184,532`(R5)다. 파티션 교체였다면 마지막 run 의 `184,532` 만 남아야 하고, 자연키 upsert 였다면 같은 source 를 두 번 넣어도 `184,532` 근처여야 하므로 두 방식은 `369,064` 와 맞지 않는다. 사전 삭제는 조건부로만 배제된다: 1차 run 이 진행 중 커밋했다면 2차 run 시작(02:58:42) 무렵 이미 들어가 있던 약 11.8만~14.2만 행(96 · 103행)이 지워져 2배가 될 수 없지만, 1차 run 이 끝에 한 번에 커밋했다면 2차 run 의 사전 삭제가 지울 행이 없어 이 경우도 2배가 나온다 — 즉 "사전 삭제는 있지만 동시 실행에 안전하지 않다"는 경우는 로그로 배제되지 않는다. 이것은 로그 산술에서 나온 추론이며 코드로 확인한 것은 아니다. |
| H3 force 가 실행 중 run 을 멈추지 않고 lock 만 우회 | **관련 코드를 찾지 못했다** | batchctl · batch-runner 의 `--force` 처리 코드 · 설정 · 런북이 저장소에 없다(3.5.1). 로그 쪽 대조는 가설과 맞는다: 9개 load run 중 `lock=bypassed` · `trigger=manual` 은 2차 run 1건뿐이고(R2 · R4), 99행에 1차 run(pid=46735)의 lock 을 알고도 `action=ignored`, 그 뒤 1차 run 은 `lock=released`(109행)까지 정상 종료했다. 즉 force 는 기존 run 을 종료시키지 않았다. 이것이 설계된 동작인지(설정) 운영자가 잘못 고른 것인지(절차)는 코드 · 런북 없이는 가를 수 없다. |

#### 3.5.4 최종 판정

**채택: H1 — `load_datamart` 의 `mode=append` 적재에 영업일 단위 멱등 처리가 없거나 동시 실행에서 깨져서, 같은 `date=2026-09-15` 로 두 번 실행된 run 의 행(각 184,532)이 `dm.daily_submission_fact` 에 그대로 누적됐다.**

- 근거 요약: 두 run 모두 같은 source · `mode=append` · SUCCESS(89 · 101 · 108 · 110행), 결과 행수 `369,064 = 2 × 184,532` 를 서로 다른 두 출처(CSV `datamart-counts.csv:24`, DQ `batch-job.log:115`)가 독립적으로 보고, 선삭제 · 롤백 흔적 0건(3.4 C2), 정확한 2배라는 값이 파티션 교체 · 자연키 upsert 를 배제(3.5.3). 사전 삭제는 1차 run 의 커밋 방식에 따라 남는다 — 이 경우 결함은 "멱등 처리가 없음"이 아니라 "멱등 처리가 동시 실행에서 깨짐"이 되며, 어느 쪽이든 같은 `date` 의 두 번째 실행이 행을 누적시켰다는 H1 의 핵심 주장은 같다.
- **확신 수준: 중간.**
  - 높음이 아닌 이유: `load_datamart` 의 적재 코드와 `dm.daily_submission_fact` DDL 을 직접 보지 못했다(저장소에 없음). 판정은 로그 · CSV 산술에서 나온 추론이다. 또 DQ 가 테이블을 직접 세는지도 코드로 확인하지 못했다.
  - 낮음이 아닌 이유: 서로 다른 호스트의 두 측정값이 정확히 2배로 일치하고, 이 2배라는 값이 파티션 교체 · 자연키 upsert 와 모순된다. 반대 방향 신호(삭제 · 롤백 · 중복키 오류)는 0건이다.
  - 확신을 높이려면: batch-runner 의 `load_datamart` 쓰기 SQL, `SHOW CREATE TABLE dm.daily_submission_fact`(readonly), `SELECT COUNT(*), COUNT(DISTINCT <자연키>) ... WHERE date='2026-09-15'`.

**채택하지 않은 가설과 사유**

| 가설 | 결과 | 사유 |
|---|---|---|
| H3 force 재실행이 lock 만 우회해 동시 실행을 만듦 | **기각(근본 원인으로서)** · 기여 요인으로 기록 | 로그상 사실로는 유지된다(같은 `date` 의 두 번째 실행이 생긴 경로). 동시 실행이 2배의 필요조건이었는지는 적재 코드에 따라 두 갈래이며, **어느 쪽인지는 코드 없이 가를 수 없다**. ① 멱등 처리 자체가 없으면 순차 재실행이어도 2배가 되므로 동시 실행은 필요조건이 아니고, H3 은 촉발 경로다. ② 사전 삭제는 있으나 동시 실행에 안전하지 않으면(1차 run 이 끝에 커밋) 순차 재실행은 184,532 로 끝났을 것이므로 동시 실행이 **필요조건이었을 수 있다**. 두 경우 모두 결함이 놓인 곳은 같은 `date` 재실행을 안전하게 처리하지 못한 `load_datamart` 적재 로직이므로 채택은 H1 로 두고, H3 은 "재실행 경로이자 ②의 경우 필요조건"으로 기록한다. 후속 조치(force 의 동작 정의, 실행 중 run 확인 절차)는 별도로 필요하다. |
| H2 테이블에 자연키 유니크 제약 없음 | **기각(채택 대상 아님)** · 판단 불가 상태 유지 | DDL 이 저장소에 없어 확인하지 못했다(3.5.1). 2배 행수가 존재한다는 것은 "중복을 막는 제약이 이번 행들에 작동하지 않았다"는 뜻일 뿐, 제약이 없는 것인지 run 별 값이 섞인 키라 통과한 것인지 가를 수 없다. H1 과 함께 성립할 수 있는 방어선 문제로, 원인 하나로 채택하지 않는다. |
| H4 grade-db replica lag 가 load 지연을 일으켜 재실행 촉발 | **기각(근본 원인으로서)** · 판단 불가 상태 유지 | 중복 자체가 아니라 재실행 계기에 대한 가설이다. 로그상 load 는 grade-db 가 아니라 extract 산출물을 읽고(3.4 C9), 02:58 이후 같은 호스트 · 같은 대상의 2차 run 은 평소의 약 90% 속도였는데 1차 run 만 30~52행/s 로 계속 느렸다(3.4 C15). 1차 run 지연의 원인은 지표 없이는 정할 수 없다. |
| H5 테이블엔 중복이 없고 집계만 합산 | **기각** | CSV 와 다른 호스트의 DQ 가 `table=dm.daily_submission_fact rows=369064` 를 보고했고(3.4 C10), DQ 7일 평균이 CSV 값과 일치한다(C11). 또 CSV 는 2차 run 이 스스로 보고한 `rows=184532` 가 아니라 `369064` 를 적었다(R5) — 합산이 아니라 테이블 행수를 기록한 것으로 읽힌다. |

#### 3.5.5 의심 동작 · 별도 조치 (판정과 별개)

- `batch-job.log:98` 에 Bearer 토큰 평문 기록(1.4절) — 토큰 폐기 · 로그 마스킹 필요.
- 09-15 영업일 분 `dm.daily_submission_fact` 에 184,532행 중복이 남아 있을 가능성. 09-16 영업일은 184,895(`pipeline-samples/datamart-counts.csv`)로 평소 범위지만, 09-15 분이 정리됐는지는 이 자료로 알 수 없다. 09-15 기준 `build_report_cache`(input 369,064, 1,271행)와 그 하위 리포트도 재생성 대상인지 확인이 필요하다.

## 4. 원인

> 3.5.4 의 채택 가설(H1)과 기여 요인(H3)을 인과 순서로 다시 쓴 것이다. 문장 번호는 5절의 근거 표와 1:1 로 맞춘다.

**배경 원인 (같은 `date` 가 두 번 실행되게 만든 조건)**

- 4-1. 09-16 01:44 부터 `extract_submissions` 가 원천 읽기 지연 경고를 내며 평소의 2배 넘게(1668s) 걸렸다.
- 4-2. 이어 02:00 에 시작한 `load_datamart` 1차 run 이 평소(202행/s)보다 크게 느린 30~80행/s 로 진행했다. **이 지연의 원인은 확인하지 못했다**(H4 판단 불가, 3.5.4).
- 4-3. 1차 run 이 02:40 에 p95 초과, 02:45 에 SLA 미완료 경보를 냈다.
- 4-4. 운영자가 "멈춘 것으로 보고" `force=true` 재실행을 요청했다. 1차 run 은 실제로는 진행 중이었다.
- 4-5. `--force` 는 1차 run 을 종료시키지 않고 lock 만 무시해, 같은 `date` 의 2차 run 이 1차 run 과 **동시에** 돌았다(H3).

**직접 원인 (행이 2배가 된 이유)**

- 4-6. 두 run 모두 같은 source 를 `mode=append` 로 같은 대상 테이블에 쓰고, 각각 184,532행으로 SUCCESS 했다.
- 4-7. `load_datamart` 에 영업일 단위 멱등 처리(사전 삭제 · 파티션 교체 · upsert)가 없거나, 있더라도 동시 실행에서 깨져서 두 run 의 행이 그대로 누적됐다(H1). 둘 중 어느 쪽인지는 코드 없이 가를 수 없다.
- 4-8. 그 결과 테이블 행수가 `369,064 = 2 × 184,532` 가 됐고, 이를 CSV 와 DQ 가 독립적으로 보고했다.
- 4-9. 하류 `build_report_cache` 는 입력 행수 불일치를 WARN 으로만 남기고 369,064행으로 캐시를 만들었다.
- 4-10. 2차 run 은 lock 소유자가 아니라 lock 해제를 건너뛰었다.

## 5. 근거 로그 줄

> 원본 로그 줄번호만 쓴다. 98행 Authorization 헤더 값은 `[TOKEN]`, 이메일 아이디는 `***` 로 가렸다.

| 4절 | 파일:줄 | 발췌 |
|---|---|---|
| 4-1 | `batch-job.log:86` | `status=RUNNING rows=19204 note="source read slow: grade-db 10.20.x.x replica lag 412s"` |
| 4-1 | `batch-job.log:87` | `job=extract_submissions date=2026-09-15 ... status=SUCCESS rows=41573 elapsed=1668s` |
| 4-2 | `batch-job.log:91` | `run_id=ldm-20260916-0200-edf5 status=RUNNING progress=25% rows=46133` (02:09:39, 시작 후 9분 36초) |
| 4-2 | `batch-job.log:92` · `:93` | `progress=38% rows=70122` (02:21:21) · `progress=51% rows=94111` (02:33:57) |
| 4-2 | 3.4절 C15 | 평소 202행/s, 1차 run 80 → 30~34 → 50~52행/s |
| 4-3 | `batch-job.log:94` | `status=RUNNING note="elapsed 40m exceeds p95 17m" rows=94111` |
| 4-3 | `batch-job.log:95` | `[sla-monitor@10.20.x.x] ... status=NOT_FINISHED sla=02:45 last_status=RUNNING` |
| 4-4 | `batch-job.log:97` | `rerun requested job=load_datamart date=2026-09-15 operator=***@example.com reason="SLA 알림 — 멈춘 것으로 보고 재실행" force=true` |
| 4-4 | `batch-job.log:96` | `run_id=ldm-20260916-0200-edf5 status=RUNNING progress=64% rows=118100` (02:47:15, 재실행 11분 전에도 진행 중) |
| 4-5 | `batch-job.log:99` | `lock=held_by run_id=ldm-20260916-0200-edf5 pid=46735 since=02:00:03 action=ignored reason="--force"` |
| 4-5 | `batch-job.log:100` | `run_id=ldm-20260916-0258-8963 status=START rows=0 trigger=manual ... lock=bypassed ... pid=47948` |
| 4-5 | `batch-job.log:103` · `:105` | 2차 run 시작 후에도 1차 run `progress=77% rows=142089` · `progress=90% rows=166078` |
| 4-6 | `batch-job.log:89` · `:101` | 두 run 모두 `step=read source=extract_submissions/2026-09-15 mode=append target=dm.daily_submission_fact` |
| 4-6 | `batch-job.log:108` | `run_id=ldm-20260916-0200-edf5 status=SUCCESS rows=184532 elapsed=4357s` |
| 4-6 | `batch-job.log:110` | `run_id=ldm-20260916-0258-8963 status=SUCCESS rows=184532 elapsed=1008s` |
| 4-7 | 3.4절 C2 · 3.5.1 | 삭제 · 덮어쓰기 · 롤백 계열 키워드 0건 / 적재 코드는 저장소에 없음 |
| 4-8 | `datamart-counts.csv:24` | `2026-09-15,load_datamart,369064,2026-09-16T03:15:31+09:00` |
| 4-8 | `batch-job.log:115` | `[dq-check@10.20.x.x] table=dm.daily_submission_fact date=2026-09-15 rows=369064 avg_7d=184617 deviation=+99%` |
| 4-9 | `batch-job.log:113` | `input_rows=369064 note="input_rows differs from extract_submissions rows=41573 x avg fan-out"` |
| 4-9 | `batch-job.log:114` | `job=build_report_cache ... status=SUCCESS rows=1271 input_rows=369064` |
| 4-10 | `batch-job.log:111` | `run_id=ldm-20260916-0258-8963 lock=release_skipped reason="not owner (bypassed)"` |

## 6. 수정안

> **지금 바로 고칠 결함 지점만** 쓴다. batch-runner · batchctl 소스는 저장소에 없어(3.5.1) 파일 경로 대신 컴포넌트와 동작으로 지정한다. 코드는 수정하지 않았다.

| # | 대상 | 무엇을 | 어떻게 |
|---|---|---|---|
| 6-1 | batch-runner `load_datamart` 쓰기 단계 (경로 미확인) | `mode=append` 적재 | 한 트랜잭션 안에서 `DELETE FROM dm.daily_submission_fact WHERE date=?` 후 INSERT, **커밋은 끝에 한 번**. 로그에 `mode=replace_date deleted=<N>` 을 남긴다(`templates/CLAUDE.data-pipeline.md:70` 규칙과 같은 방식). |
| 6-2 | batch-runner lock 처리의 `--force` 분기 (경로 미확인) | lock 소유 run 이 살아 있어도 `action=ignored` | lock 파일의 `pid` 가 살아 있으면 force 여도 거부하고 batchctl API 가 `409 Conflict` 를 돌려준다. 우회는 소유 pid 가 죽은 **stale lock** 일 때만 허용한다. |
| 6-3 | batchctl 요청 로깅 (경로 미확인) | `header="Authorization: [TOKEN]"` 평문 기록(`batch-job.log:98`) | 헤더 값을 `[TOKEN]` 으로 마스킹하고, 노출된 토큰은 즉시 폐기 · 재발급한다. |
| 6-4 | 데이터 복구 (영업일 09-15) | 중복 184,532행 · 리포트 캐시 | 먼저 `readonly` 계정으로 `COUNT(*)` · `COUNT(DISTINCT <자연키>)` 를 확인한다. 6-1 반영 후 09-15 를 1회 재적재하고 `build_report_cache` 09-15 를 재생성한다. 쓰기 계정 `app` 은 쓰지 않는다. |

## 7. 재발 방지

> 6절의 개별 패치와 별개로, **같은 유형(중복 실행 · 비멱등 적재)이 다른 job 에서도 생기지 않게 하는 구조적 장치**다.

- 7-1. **DB 제약으로 중복 거부:** `dm.daily_submission_fact` 에 자연키(영업일 · 학생 · 제출 등) `UNIQUE` 를 둔다. 적재 로직이 틀려도 DB 가 막는 마지막 방어선이다. 단 현재 DDL 은 미확인(H2)이라 `SHOW CREATE TABLE` 결과를 본 뒤 정한다.
- 7-2. **실행 레지스트리:** 파일 lock 대신 DB 테이블에 `(job, date)` 당 활성 run 을 하나만 허용하는 키를 두고, heartbeat 가 끊긴 run 만 만료시킨다. 우회 옵션 자체를 없앤다.
- 7-3. **하류 게이트:** `build_report_cache` 의 입력 행수 검사(`batch-job.log:113`)를 WARN 후 진행이 아니라 **FAIL · 보류**로 바꾼다. 상류 이상이 하류로 전파되지 않게 한다.
- 7-4. **멱등성 테스트를 CI 에 고정:** 모든 적재 job 에 "같은 `date` 로 두 번 실행 → 건수 동일" 테스트를 둔다(`templates/CLAUDE.data-pipeline.md:56` 규칙을 테스트로 강제).
- 7-5. **런북 · 명령 분리:** SLA 경보 대응 절차에 "실행 중 run 의 진행률 · pid 확인"을 첫 단계로 넣고, batchctl 에서 "기존 run 종료 후 재시작"과 "lock 우회"를 다른 명령으로 나눈다. 우회는 2인 승인으로 둔다.

## 8. 모니터링 항목

> **감지만** 다룬다. "발화 시각"은 이번 로그에 임계값을 적용했을 때의 시각이다. `(가정)` 은 로그 줄이 아니라 타이머로 판정한다고 가정한 시각이다.

| 지표 | 임계값 제안 | 이번 발화 시각 | 실제 감지 대비 |
|---|---|---|---|
| 원천 replica lag | > 300s | 01:44:07 (`:86`, 412s) | 실제 별도 경보 없음. 지연 인지 56분 앞당김 |
| extract 소요 시간 | > 15분 (평소 601~736s) | 01:45:02 (가정, 01:30:02 + 15분) | 실제 경보 없음 |
| load 처리 속도 | 진행 줄 기준 7일 평균(202행/s)의 50% 미만 | 02:09:39 (`:91`, 80행/s) | 02:40 p95 경보보다 30분 빠름 |
| load 경과 시간 | p95(17분) 초과, 타이머 판정 | 02:17:03 (가정) | 실제는 로그 줄이 찍힌 02:40:03(40분)에 발화 → 23분 빠름 |
| 같은 `(job, date)` 동시 run 수 | ≥ 2 즉시 경보 | 02:58:42 (`:99` · `:100`) | **중복 감지를 07:00:04 → 02:58:42, 약 4시간 앞당김** |
| lock 이상 이벤트 | `lock=held_by ... action=ignored` · `bypassed` · `release_skipped` 1건 이상 | 02:58:42 (`:99`), 03:15:31 (`:111`) | 위와 같음 |
| 적재 fan-out (load rows ÷ extract rows) | 평소 약 4.4, 3.5~5.5 밖이면 경보 | 03:15:31 (`csv:24`, 369,064 ÷ 41,573 = 8.88) | DQ(07:00:04)보다 3시간 45분 빠름 |
| 적재 직후 행수 편차 | 7일 평균 대비 ±10% 초과, **적재 완료 직후** 판정 | 03:15:31 (`csv:24`, +99%) | 같은 검사를 07:00 → 적재 직후로 옮기면 3시간 45분 빠름 |

- 핵심 권고: 지연 경보(02:40 · 02:45)는 이미 있었지만 **중복 실행 자체를 보는 지표가 없어** 중복은 4시간 뒤 DQ 에서야 잡혔다. 동시 run 수 · lock 이상 이벤트 경보를 먼저 둔다.

## 9. 확인하지 못한 것

**로그가 없어 판단하지 못한 것**

- `load_datamart` 의 실제 쓰기 SQL 과 커밋 단위 — 멱등 처리가 "없음"인지 "동시 실행에서 깨짐"인지, 따라서 동시 실행(H3)이 2배의 필요조건이었는지(3.5.4).
- `dm.daily_submission_fact` DDL — 자연키 제약 유무(H2).
- DQ 검사 · CSV `row_count` 가 테이블을 직접 `COUNT` 하는지(H5 재개 여부).
- 1차 run 이 02:00~02:58 에 느렸던 원인 — 원천 replica lag · 대상 DB · 배치 호스트 IO/락 대기 중 무엇인지(H4).
- `--force` 의 문서상 정의와 런북 — 설계된 동작인지 절차 미준수인지.
- 09-15 분 중복이 이후 정리됐는지, 369,064행 기준 리포트 캐시를 누가 소비했는지. 07:00:04 이후 대응 기록.
- 호스트 간(batch-runner · batchctl · sla-monitor/dq-check 호스트) 시계 동기 여부.

**다음을 위해 남겨야 할 로그**

- `load_datamart` 단계별(read · write · commit) 소요 시간과 커밋 지점의 `committed=<N>`.
- 적재 시작 시 사전 삭제 결과 `deleted=<N>` (멱등 처리가 실제로 돌았는지 증명).
- lock 판정 근거: 소유 pid 생존 여부, heartbeat 시각.
- batchctl 감사 로그: 재실행 전 확인한 run 상태, 승인자. 토큰 · 개인 식별자는 마스킹.
- DQ 검사 쿼리 원문과 CSV `row_count` 의 정의(run 적재 건수인지 테이블 행수인지 — 3.5.2 R5 에서 드러난 모호함). 가능하면 두 값을 별도 필드로.
- 원천 replica lag · 대상 DB 락 대기 지표를 배치 시간대(01:30~03:45)에 1분 단위로 보관.

마스킹 적용: IP 33건(10.20.x.x 29 · 역할 별칭 4) · 이메일 2건 · 학생 식별자 10건(1명) · 토큰 1건 · 비밀번호/접속 문자열 0건
