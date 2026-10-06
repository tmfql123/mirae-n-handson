package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ActiveProfiles("test")
class ItemSearchCriteriaTest {

    @ParameterizedTest(name = "{0}은 {1}로 변환된다")
    @CsvSource({
        "3a, 3",
        "3.9, 3",
        "03, 3",
        "+3, 3",
        "abc, 0",
        "0, 0",
        "6, 6",
        "-1, -1",
        "1e1, 10"
    })
    @DisplayName("PHP 정수 변환 규칙을 재현한다")
    void convertsLevelLikePhpIntval(String raw, int expected) {
        assertThat(criteria("level", raw).level()).isEqualTo(expected);
    }

    @Test
    @DisplayName("PHP trim 문자만 제거하고 비분리 공백은 보존한다")
    void trimsOnlyPhpTrimCharacters() {
        ItemSearchCriteria criteria = criteria("q", "\t\n\r\0\u000B  \u00A0검색어\u00A0  \u000B\r\n\t");

        assertThat(criteria.keyword()).isEqualTo("\u00A0검색어\u00A0");
    }

    @Test
    @DisplayName("키워드는 이모지를 한 글자로 세어 100 코드포인트에서 자른다")
    void truncatesKeywordByCodePoint() {
        String raw = "가".repeat(99) + "😀" + "나";

        ItemSearchCriteria criteria = criteria("q", raw);

        assertThat(criteria.keyword()).hasSize(101);
        assertThat(criteria.keyword().codePointCount(0, criteria.keyword().length())).isEqualTo(100);
        assertThat(criteria.keyword()).endsWith("😀");
    }

    @Test
    @DisplayName("태그는 50 코드포인트에서 자른다")
    void truncatesTagAtFiftyCodePoints() {
        ItemSearchCriteria criteria = criteria("tag", "태".repeat(49) + "😀" + "끝");

        assertThat(criteria.tag().codePointCount(0, criteria.tag().length())).isEqualTo(50);
        assertThat(criteria.tag()).endsWith("😀");
    }

    @Test
    @DisplayName("배열 키는 첫 값을 쓰고 평문 반복 값보다 우선한다")
    void usesFirstArrayValueBeforePlainValues() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag", "평문첫값");
        params.add("tag", "평문마지막값");
        params.add("tag[]", "계산");
        params.add("tag[]", "도형");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("계산");
    }

    @Test
    @DisplayName("배열 키가 없으면 평문 반복 값의 마지막 값을 쓴다")
    void usesLastPlainValue() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag", "계산");
        params.add("tag", "도형");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("도형");
    }

    @Test
    @DisplayName("배열 키 다음에 평문 키가 오면 평문 값을 쓴다")
    void usesPlainValueWhenPlainKeyComesAfterArrayKey() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag[]", "a");
        params.add("tag", "b");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("b");
    }

    @Test
    @DisplayName("평문 키 다음에 배열 키가 오면 배열 값을 쓴다")
    void usesArrayValueWhenArrayKeyComesAfterPlainKey() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag", "b");
        params.add("tag[]", "a");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("a");
    }

    @Test
    @DisplayName("같은 인덱스 배열 키가 반복되면 마지막 값으로 덮어쓴다")
    void overwritesSameIndexedArrayKey() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag[x]", "a");
        params.add("tag[x]", "b");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("b");
    }

    @Test
    @DisplayName("빈 인덱스 배열 키가 먼저 나오면 인덱스 키보다 그 첫 값을 쓴다")
    void usesFirstAppendedArrayValueBeforeIndexedKey() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag[]", "a");
        params.add("tag[x]", "b");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("a");
    }

    @Test
    @DisplayName("인덱스 키가 섞여도 첫 칸은 처음 나온 인덱스의 마지막 값이다")
    void usesLastValueOfFirstIndexedKey() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag[x]", "a");
        params.add("tag[y]", "b");
        params.add("tag[x]", "c");

        assertThat(ItemSearchCriteria.from(params).tag()).isEqualTo("c");
    }

    @Test
    @DisplayName("화이트리스트 밖의 정렬 조건은 생성 단계에서 거부한다")
    void rejectsOrderByOutsideWhitelist() {
        assertThatThrownBy(() -> new ItemSearchCriteria(null, null, null, null, "id; DROP TABLE item", 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemSearchCriteria(null, null, null, null, "id", 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정렬 조건이 null 이면 생성 단계에서 거부한다")
    void rejectsNullOrderBy() {
        assertThatThrownBy(() -> new ItemSearchCriteria(null, null, null, null, null, 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 offset 은 생성 단계에서 거부한다")
    void rejectsNegativeOffset() {
        assertThatThrownBy(() -> new ItemSearchCriteria(null, null, null, null, "id ASC", 1, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "sort={0}, dir={1}")
    @MethodSource("sortCases")
    @DisplayName("정렬 기준과 방향을 화이트리스트 SQL 조각으로 바꾼다")
    void buildsWhitelistedOrderBy(String sort, String dir, String expected) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("sort", sort);
        params.add("dir", dir);

        assertThat(ItemSearchCriteria.from(params).orderBy()).isEqualTo(expected);
    }

    static Stream<Arguments> sortCases() {
        return Stream.of(
            Arguments.of("ID", "DESC", "id DESC"),
            Arguments.of("id", "asc", "id ASC"),
            Arguments.of("title", "desc", "title DESC, id ASC"),
            Arguments.of("title", "", "title ASC, id ASC"),
            Arguments.of("unit", "desc", "unit_code DESC, level DESC, id ASC"),
            Arguments.of("unit", "asc", "unit_code ASC, level DESC, id ASC"),
            Arguments.of("level", "asc", "level ASC, id ASC"),
            Arguments.of("level", "desc", "level DESC, id ASC"),
            Arguments.of("created", "asc", "created_at ASC, id ASC"),
            Arguments.of("created", "desc", "created_at DESC, id ASC"),
            Arguments.of("hack", "up", "level DESC, id ASC"),
            Arguments.of("", "", "level DESC, id ASC")
        );
    }

    @ParameterizedTest(name = "page={0}은 {1}페이지")
    @CsvSource(value = {
        "<null>|1",
        "''|1",
        "-1|1",
        "0|1",
        "2|2",
        "'2\n'|2",
        "' 2'|1",
        "1000|999",
        "99999999999999999999|999"
    }, delimiter = '|', nullValues = "<null>")
    @DisplayName("페이지 입력을 1부터 999 사이로 보정한다")
    void normalizesPage(String raw, int expected) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (raw != null) {
            params.add("page", raw);
        }

        ItemSearchCriteria criteria = ItemSearchCriteria.from(params);

        assertThat(criteria.page()).isEqualTo(expected);
        assertThat(criteria.offset()).isEqualTo((expected - 1) * ItemSearchCriteria.PAGE_SIZE);
    }

    private static ItemSearchCriteria criteria(String name, String value) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add(name, value);
        return ItemSearchCriteria.from(params);
    }
}
