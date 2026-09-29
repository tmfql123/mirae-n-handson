package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.util.LinkedMultiValueMap;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ItemSearchRepository.class)
@ActiveProfiles("test")
@Sql({"/sql/v_item_public.sql", "/sql/item-search-seed.sql"})
class ItemSearchRepositoryTest {

    @Autowired
    private ItemSearchRepository itemSearchRepository;

    @Test
    @DisplayName("기본 검색은 난이도 5와 비공개 문항을 제외하고 정해진 순서로 20건을 반환한다")
    void findsDefaultPageInLegacyOrder() {
        ItemSearchCriteria criteria = criteria();

        List<ItemSearchRow> rows = itemSearchRepository.findPage(criteria);

        assertThat(itemSearchRepository.count(criteria)).isEqualTo(20);
        assertThat(rows).hasSize(20);
        assertThat(rows).extracting(ItemSearchRow::id)
            .containsExactly(18, 19, 20, 21, 22, 12, 13, 14, 15, 16, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5);
        assertThat(rows).extracting(ItemSearchRow::id).doesNotContain(11, 17, 23, 30, 24);
    }

    @Test
    @DisplayName("난이도 5를 명시하면 공개 문항을 조회하고 태그 없는 문항은 빈 배열로 매핑한다")
    void includesExplicitLevelFiveAndMapsMissingTags() {
        ItemSearchCriteria criteria = criteria("level", "5");

        List<ItemSearchRow> rows = itemSearchRepository.findPage(criteria);

        assertThat(itemSearchRepository.count(criteria)).isEqualTo(7);
        assertThat(rows).extracting(ItemSearchRow::id).contains(24, 25, 26, 27, 28, 29, 31).doesNotContain(30);
        assertThat(rows).filteredOn(row -> row.id().equals(31)).singleElement()
            .extracting(ItemSearchRow::tags).isEqualTo(List.of());
    }

    @Test
    @DisplayName("태그 이름은 태그 ID 순서를 유지한다")
    void keepsTagOrderFromView() {
        ItemSearchCriteria criteria = criteria("q", "분모가 다른");

        assertThat(itemSearchRepository.findPage(criteria)).singleElement()
            .extracting(ItemSearchRow::tags).isEqualTo(List.of("계산", "오답률높음"));
    }

    @Test
    @DisplayName("단원과 난이도 조건은 AND로 결합한다")
    void filtersByUnitAndLevel() {
        ItemSearchCriteria criteria = criteria("unit", "M5-1", "level", "2");

        assertThat(itemSearchRepository.count(criteria)).isEqualTo(1);
        assertThat(itemSearchRepository.findPage(criteria)).extracting(ItemSearchRow::id).containsExactly(6);
        assertThat(itemSearchRepository.findPage(criteria)).extracting(ItemSearchRow::unit).containsOnly("M5-1");
    }

    @Test
    @DisplayName("태그 조건은 EXISTS로 정확히 일치하는 공개 문항을 찾는다")
    void filtersByExactTag() {
        ItemSearchCriteria criteria = criteria("tag", "계산");

        assertThat(itemSearchRepository.count(criteria)).isEqualTo(13);
        assertThat(itemSearchRepository.findPage(criteria)).allSatisfy(row ->
            assertThat(row.tags()).contains("계산"));
    }

    @Test
    @DisplayName("제목 오름차순은 같은 제목일 때 ID 오름차순을 사용한다")
    void sortsByTitleAscending() {
        ItemSearchCriteria criteria = criteria("sort", "title", "dir", "asc");

        assertThat(itemSearchRepository.findPage(criteria)).extracting(ItemSearchRow::title).isSorted();
    }

    @Test
    @DisplayName("2페이지는 목록이 비어도 전체 건수 20건을 유지한다")
    void keepsTotalCountOutsideCurrentPage() {
        ItemSearchCriteria criteria = criteria("page", "2");

        assertThat(itemSearchRepository.count(criteria)).isEqualTo(20);
        assertThat(itemSearchRepository.findPage(criteria)).isEmpty();
    }

    private static ItemSearchCriteria criteria(String... namesAndValues) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            params.add(namesAndValues[index], namesAndValues[index + 1]);
        }
        return ItemSearchCriteria.from(params);
    }
}
