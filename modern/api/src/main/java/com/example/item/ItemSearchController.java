package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API — 레거시 {@code search.php} 이관. 파라미터는 PHP 방식(배열 · 반복 키)으로 해석해야 하므로
 * 타입 변환 없이 원문 그대로 서비스에 넘긴다. 어떤 입력이든 200 으로 응답한다.
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search} — q · unit · level · tag · sort · dir · page (레거시와 같은 이름). */
    @GetMapping("/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(params);
    }
}
