// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 케이스 설계 근거: docs/item-bank/BUSINESS-RULES.md 의 규칙 ID(BR-xx)를 케이스 이름에 적었다.
// 시드(db/mariadb/init/02-seed.sql) 기준 — 공개(A) · 난이도 5 미만 문항이 정확히 20건(= 한 페이지 크기)이다.
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
//   테스트 코드에 주소를 직접 적지 않는다.
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const search = (params) => fetchNormalized(MODULE, PATH, params);

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('단원 M5-1 + 난이도 2 — 공개 문항만 · AND 결합 · 단원 완전 일치 · 난이도 일치 (BR-01 BR-02 BR-05 BR-07)', async () => {
      expect(await search({ unit: 'M5-1', level: '2' })).toMatchSnapshot();
    });

    it('키워드 분수 + 제목 오름차순 — 제목·지문 부분 일치, 미지정 난이도 5 제외, title ASC id ASC (BR-04 BR-10 BR-13)', async () => {
      expect(await search({ q: '분수', sort: 'title', dir: 'asc' })).toMatchSnapshot();
    });

    it('태그 계산 + 난이도 5 명시 — 난이도 5 를 명시하면 조회됨, 기본 정렬 (BR-07 BR-11 BR-12)', async () => {
      expect(await search({ tag: '계산', level: '5' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 0 — 하한 바로 아래, 미지정(BR-10)이 아니라 범위 밖(BR-08)으로 처리 (BR-08 BR-09)', async () => {
      expect(await search({ level: '0' })).toMatchSnapshot();
    });

    it('난이도 6 — 상한 바로 위, 경고 후 level = 6 조회 (BR-07 BR-08)', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('2페이지 — 기본 검색 20건이 한 페이지를 막 채우는 경계 (BR-10 BR-14)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('페이지 1000 — 최대 999 를 1 넘는 값, 999 로 보정 (BR-14)', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('키워드 101자 — 100자 제한을 1자 넘김, 100자로 자름 (BR-04)', async () => {
      expect(await search({ q: '가'.repeat(101) })).toMatchSnapshot();
    });

    it('한 글자 키워드 수 — 한 글자 경고 기준, 경고 후 검색 (BR-04)', async () => {
      expect(await search({ q: '수' })).toMatchSnapshot();
    });

    it('난이도 3a — 정규식 불일치지만 (int) 로 3 이 되어 난이도 3 조회 (BR-08)', async () => {
      expect(await search({ level: '3a' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 전부 누락 — 기본 검색: 공개 · 난이도 5 제외 · level DESC id ASC · 1페이지 20건 (BR-01 BR-10 BR-12 BR-14)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('파라미터 전부 빈 값 — 키 없음과 같은지 (BR-09 BR-10 BR-12 BR-14)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('공백만 있는 난이도 — trim 후 빈 값이 되어 미지정(난이도 5 제외)으로 처리 (BR-09 BR-10)', async () => {
      expect(await search({ level: '  ' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 페이지 -1 — 경고 후 1페이지 (BR-14)', async () => {
      expect(await search({ page: '-1' })).toMatchSnapshot();
    });

    it('아주 긴 키워드 2000자 — maxlength 우회, 서버에서 자르고 5xx 없음 (BR-04)', async () => {
      expect(await search({ q: 'a'.repeat(2000) })).toMatchSnapshot();
    });

    it('없는 단원 코드 Z9-9 — 형식은 맞지만 미등록, 거부 없이 경고 후 0건 (BR-05)', async () => {
      expect(await search({ unit: 'Z9-9' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 m5-1 — 콜레이션상 대문자와 일치하는지 실제 동작 고정 (BR-05 BR-06)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('배열 태그 + 모르는 정렬·방향 — 첫 태그만 사용, 경고 후 기본 정렬 (BR-03 BR-11 BR-12 BR-13)', async () => {
      // PHP 는 tag[]=… 형식일 때만 배열로 받는다. tag=…&tag=… 는 마지막 값 하나가 된다.
      expect(await search({ 'tag[]': ['계산', '도형'], sort: 'hack', dir: 'up' })).toMatchSnapshot();
    });
  });
});
