package com.example.item;

import java.util.List;

/** 검색 응답. 필드 이름은 동작 보존 테스트의 정규화 결과 {@code {status, rows, count, message}} 와 같다. */
public record ItemSearchResponse(int status, List<ItemSearchRow> rows, long count, String message) {
}
