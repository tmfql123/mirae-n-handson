package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("검색 응답은 지정된 필드만 JSON으로 반환한다")
    void returnsSearchResponseJson() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(
            200,
            List.of(new ItemSearchRow(6, "분모가 다른 분수의 덧셈", "M5-1", 2, List.of("계산", "오답률높음"))),
            1,
            null));

        mockMvc.perform(get("/api/items/search"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.rows[0].id").value(6))
            .andExpect(jsonPath("$.rows[0].title").value("분모가 다른 분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(2))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").value((Object) null));
    }

    @Test
    @DisplayName("인코딩된 배열 파라미터와 이상한 난이도를 문자열 그대로 서비스에 전달한다")
    void passesRawMultiValueParametersToService() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(200, List.of(), 0, "검색 결과가 없습니다"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);

        mockMvc.perform(get(URI.create("/api/items/search?tag%5B%5D=a&tag%5B%5D=b&level=3a")))
            .andExpect(status().isOk());

        verify(itemSearchService).search(captor.capture());
        assertThat(captor.getValue().get("tag[]")).containsExactly("a", "b");
        assertThat(captor.getValue().getFirst("level")).isEqualTo("3a");
    }

    @Test
    @DisplayName("레거시 검색 경로는 매핑하지 않아 404를 반환한다")
    void leavesLegacySearchPathUnmapped() throws Exception {
        mockMvc.perform(get("/search.php"))
            .andExpect(status().isNotFound());
    }
}
