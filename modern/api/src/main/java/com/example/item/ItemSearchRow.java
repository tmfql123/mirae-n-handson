package com.example.item;

import java.util.List;

/** 검색 결과 한 행. 정규화가 쓰는 다섯 필드만 둔다. {@code unit} 은 DB 의 단원 코드, {@code tags} 는 태그 id 순. */
public record ItemSearchRow(Integer id, String title, String unit, Integer level, List<String> tags) {
}
