# 문항 은행(PHP) 비즈니스 규칙 — 코드 기준 교차 검증용 추출

- 대상: `legacy/item-bank-php/` 의 `search.php` · `register.php` · `units.php` · `inc/db.php` · `inc/layout.php` · `index.php`
- 읽지 않은 것: `docs/` 전체, `vendor/`(외부 라이브러리), 이 폴더 밖의 파일(DB 스키마 · 뷰 정의 포함)
- 원칙: 코드에 있는 것만 적었고, 주석과 코드가 다르면 코드를 기준으로 했다.
- 줄번호는 모두 `legacy/item-bank-php/` 기준 상대 경로다.

## A. 검색 조건 조합 (단원 · 난이도 · 태그 · 키워드)

### CX-01 — 조건은 서로 AND 로 결합된다
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:245`
```php
$where = " WHERE 1=1";
$where .= " AND unit_code = ?";
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
```
- 참고: OR 는 키워드 한 곳(제목 또는 지문)에만 있다(CX-02).

### CX-02 — 키워드는 제목 또는 지문에 부분 일치(LIKE)하며, 앞뒤 공백을 제거하고 100자에서 자른다
- 근거: `search.php:85-89`, `search.php:97-98`
```php
$q = trim($q);
if (mb_strlen($q, 'UTF-8') > 100) { $q = mb_substr($q, 0, 100, 'UTF-8'); ...
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-03 — 키워드의 `%` 와 `_` 는 이스케이프되지 않고 와일드카드로 동작한다
- 근거: `search.php:94-97`
```php
if (strpos($q, '%') !== false || strpos($q, '_') !== false) {
    $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
$like = '%' . $q . '%';
```

### CX-04 — 단원은 단원 코드와 정확히 일치(`=`)하는 문항만 찾는다. 형식이 틀리거나 미등록 코드여도 거부하지 않고 조회한다(경고만 표시)
- 근거: `search.php:110-120`, `search.php:141-143`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) { $warnings[] = '단원 코드 형식이 올바르지 않습니다. (예: M5-1)'; }
$where .= " AND unit_code = ?";
if ($unitName === '') { $warnings[] = '등록되지 않은 단원 코드입니다: ' . $unit;
```
- 참고: 소문자 입력도 대문자로 바꾸지 않고 입력값 그대로 조회한다(`search.php:115-117`).

### CX-05 — 난이도를 비우면 "전체"가 아니라 난이도 5(최상)가 빠진다 (`level < 5`) ⚠ 주석과 코드 불일치
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```
- 주석은 "1~5 모두 포함"이지만 코드는 level 5 문항을 제외한다. 이 규칙에 따라 난이도 미선택 검색과 단원 목록의 건수는 달라질 수 있다.
- 폼의 "전체" 옵션(`search.php:192`)도 같은 빈값으로 넘어가므로 화면에서 난이도 5 문항을 보려면 5 를 직접 선택해야 한다.

### CX-06 — 난이도가 1~5 정수면 그 값과 정확히 일치하는 문항만 찾는다
- 근거: `search.php:217-222`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```

### CX-07 — 난이도가 1~5 밖의 값이면 거부하지 않고 경고 후 정수로 바꿔 `=` 비교한다
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```
- 참고: 숫자가 아닌 문자열은 `(int)` 변환으로 0 이 된다.

### CX-08 — 태그는 태그 이름이 정확히 일치하는 태그가 붙은 문항만 찾는다(부분 일치 없음). 50자에서 자르며, 미등록 태그여도 조회는 그대로 하고 경고만 표시한다
- 근거: `search.php:236-248`, `search.php:258-260`
```php
if (mb_strlen($tag, 'UTF-8') > 50) { $tag = mb_substr($tag, 0, 50, 'UTF-8'); ...
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```
- 태그는 하나만 받는다(다중 태그 조합 없음).

### CX-09 — 각 파라미터가 배열로 오면 첫 값만 쓴다
- 근거: `search.php:54-56` (q · unit · level · tag · sort · dir · page 가 모두 같은 패턴)
```php
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

## B. 검색 대상과 제외 조건

### CX-10 — 검색 · 건수 조회는 원본 `item` 테이블이 아니라 `v_item_public` 뷰만 대상으로 한다
- 근거: `search.php:522`, `search.php:526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```
- 이 뷰가 어떤 문항을 제외하는지는 `item-bank-php/` 안의 코드로는 확인되지 않는다(뷰 정의는 폴더 밖). 주석 기준 추정은 CX-11, CX-12 참고.

### CX-11 — 단원 목록의 "공개 문항 수"는 `status = 'A'` 인 문항만 센다
- 근거: `units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```
- 주석의 "삭제 · 검수중" 이 어떤 status 코드인지는 이 코드만으로는 알 수 없다(등록 시 `'R'` 만 확인됨, CX-19). 이 규칙은 검색(CX-10)과 같은 기준인지 코드로는 검증되지 않는다. 검색은 `level < 5`(CX-05)가 걸려 있어 건수가 서로 다를 수 있다.

## C. 목록 정렬 · 페이지

### CX-12 — 기본 정렬은 난이도 내림차순, 같으면 ID 오름차순이다 (`sort` 를 비웠거나 알 수 없는 값일 때 동일)
- 근거: `search.php:317-320`, `search.php:322-326`
```php
case '':
    $orderBy = " ORDER BY level DESC, id ASC";
default:
    $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
```

### CX-13 — 정렬 기준 5종(id · title · unit · level · created)의 SQL 과 기본 방향
- 근거: `search.php:277-315`
```php
case 'unit':  ... " ORDER BY unit_code ASC, level DESC, id ASC"   // asc
case 'level': ... " ORDER BY level DESC, id ASC"                   // dir 이 asc 가 아니면 DESC
case 'created': ... " ORDER BY created_at DESC, id ASC"            // dir 이 asc 가 아니면 DESC
```
- `id` · `title` · `unit` 은 `dir=desc` 일 때만 내림차순, 아니면 오름차순이다. `level` · `created` 는 `dir=asc` 일 때만 오름차순, 아니면 내림차순이다.
- `title` · `unit` · `level` · `created` 는 동률일 때 `id ASC` 가 붙는다. `unit` 은 `dir` 과 무관하게 `level DESC` 가 두 번째 키다. (`unit` · `level` · `created` 는 `dir=desc` 여도 `id` 가 ASC.)
- `dir` 이 `asc`/`desc` 가 아니면 경고 후 빈값으로 처리한다(`search.php:268-273`).

### CX-14 — 한 페이지 20건, 페이지 번호는 1~999 로 보정된다
- 근거: `search.php:336-349`, `search.php:523`
```php
if ($page < 1) { $page = 1; }
if ($page > 999) { $page = 999; ...
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## D. 등록 검증 (`register.php`)

### CX-15 — 제목은 앞뒤 공백 제거 후 5자 이상 200자 이하여야 한다 (공백만 제거하고 글자 수로 판단, 내부 공백은 글자로 센다)
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) { $errors[] = '제목은 5자 이상 입력해야 합니다.'; }
if (mb_strlen($title, 'UTF-8') > 200) { $errors[] = '제목은 200자를 넘을 수 없습니다.'; }
```

### CX-16 — 지문은 앞뒤 공백 제거 후 비어 있으면 안 된다
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') { $errors[] = '지문을 입력해야 합니다.'; }
```

### CX-17 — 단원은 `unit` 테이블에 존재하는 id 여야 한다
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) { $unitOk = true; }
if (!$unitOk) { $errors[] = '단원을 선택해야 합니다.'; }
```
- 검색과 달리 여기서는 단원 코드가 아니라 단원 id 로 받는다.

### CX-18 — 난이도는 `1`~`5` 한 자리 정수 문자열이어야 하며, 아니면 등록을 거부한다
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) { $errors[] = '난이도는 1~5 사이여야 합니다.'; }
```
- 검색(CX-07)은 범위 밖 값도 조회하지만 등록은 거부한다.

### CX-19 — 태그는 선택 사항이며, 목록에 없는 태그 id 는 오류 없이 조용히 버려진다
- 근거: `register.php:74-81`, `register.php:104-113`
```php
if ((string)$t['id'] === (string)$tid) { $validTagIds[] = (int)$tid; }
if (!empty($validTagIds)) { $stmt = $conn->prepare("INSERT INTO item_tag (item_id, tag_id) VALUES (?, ?)");
```
- 태그 개수 상한이나 중복 제거 코드는 없다.

### CX-20 — 새 문항은 항상 상태 `'R'`(검수중)로 저장된다. 주석은 검수 완료 전까지 검색에 나오지 않는다고 설명한다
- 근거: `register.php:84`, `register.php:93-94`
```php
// 등록 직후에는 검수중(R) 상태로 들어간다 → 검수 완료 전까지 검색 화면에 나오지 않는다.
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
```
- "검색에 나오지 않는다"는 것은 뷰 `v_item_public` 정의에 달려 있고(CX-10), 이 폴더의 코드만으로는 확인되지 않는다.

### CX-21 — 문항 id 는 `MAX(id)+1` 로 채번한다 (트랜잭션 안에서 `FOR UPDATE`)
- 근거: `register.php:85-90`
```php
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
```

## 확인하지 못한 것 / 범위 메모

- 요청에 있던 "수정 시의 검증": `legacy/item-bank-php/` 에서 문항 수정 · 삭제 · 상태 변경(검수 완료) 코드를 찾지 못했다(`edit.php` · `update.php` · `item.php` · `api.php` 는 없음). 이 폴더에서 확인한 파일은 위 6개이며, Bash 분류기 오류로 디렉터리 전체 목록은 뽑지 못해 다른 PHP 파일이 더 있을 가능성은 배제하지 못했다.
- `v_item_public` 뷰의 제외 조건(status 등)은 이 폴더 밖이라 다루지 않았다(CX-10).
- `inc/db.php` 의 DB 계정 기본값 `app` 은 비즈니스 규칙이 아니라 접속 설정이라 제외했다.
