package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ItemSearchRowTest {

    @Test
    @DisplayName("검색 행 레코드는 다섯 필드 값을 보존한다")
    void preservesRowValues() {
        ItemSearchRow row = new ItemSearchRow(6, "분모가 다른 분수의 덧셈", "M5-1", 2, List.of("계산"));

        assertThat(row.id()).isEqualTo(6);
        assertThat(row.title()).isEqualTo("분모가 다른 분수의 덧셈");
        assertThat(row.unit()).isEqualTo("M5-1");
        assertThat(row.level()).isEqualTo(2);
        assertThat(row.tags()).containsExactly("계산");
    }
}
