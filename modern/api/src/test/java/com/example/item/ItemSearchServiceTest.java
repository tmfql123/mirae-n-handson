package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemSearchRepository itemSearchRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("검색 결과가 없으면 상태 200과 빈 목록과 안내 문구를 반환한다")
    void returnsEmptyResultMessage() {
        when(itemSearchRepository.count(any())).thenReturn(0L);
        when(itemSearchRepository.findPage(any())).thenReturn(List.of());

        ItemSearchResponse response = itemSearchService.search(new LinkedMultiValueMap<>());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository).findPage(any());
    }

    @Test
    @DisplayName("검색 결과가 있으면 저장소 전체 건수와 현재 페이지 목록을 반환한다")
    void returnsRepositoryCountAndRows() {
        ItemSearchRow row = new ItemSearchRow(6, "분모가 다른 분수의 덧셈", "M5-1", 2, List.of("계산"));
        when(itemSearchRepository.count(any())).thenReturn(25L);
        when(itemSearchRepository.findPage(any())).thenReturn(List.of(row));

        ItemSearchResponse response = itemSearchService.search(new LinkedMultiValueMap<>());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.rows()).containsExactly(row);
        assertThat(response.count()).isEqualTo(25);
        assertThat(response.message()).isNull();
    }
}
