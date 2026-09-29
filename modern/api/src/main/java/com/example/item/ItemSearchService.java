package com.example.item;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/** 문항 검색 서비스. 건수는 페이지와 무관한 전체 건수이고, 0건일 때만 안내 문구를 채운다. */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemSearchRepository itemSearchRepository;

    public ItemSearchService(ItemSearchRepository itemSearchRepository) {
        this.itemSearchRepository = itemSearchRepository;
    }

    public ItemSearchResponse search(MultiValueMap<String, String> params) {
        ItemSearchCriteria criteria = ItemSearchCriteria.from(params);
        long count = itemSearchRepository.count(criteria);
        List<ItemSearchRow> rows = itemSearchRepository.findPage(criteria);
        log.debug("item search page {} matched {} items", criteria.page(), count);
        String message = count == 0 ? "검색 결과가 없습니다" : null;
        return new ItemSearchResponse(200, rows, count, message);
    }
}
