package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ItemSearchResponseTest {

    @Test
    @DisplayName("검색 응답 레코드는 상태와 목록과 전체 건수와 메시지를 보존한다")
    void preservesResponseValues() {
        ItemSearchRow row = new ItemSearchRow(6, "제목", "M5-1", 2, List.of("계산"));

        ItemSearchResponse response = new ItemSearchResponse(200, List.of(row), 1, null);

        assertThat(response).isEqualTo(new ItemSearchResponse(200, List.of(row), 1, null));
    }
}
