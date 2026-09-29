package com.example.item;

import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 문항 검색 SQL. 레거시와 같은 공개 문항 뷰 {@code v_item_public} 을 읽는다(BR-01).
 * 정렬 · 비교는 DB 콜레이션을 따라야 하므로 자바에서 다시 정렬하지 않는다.
 */
@Repository
public class ItemSearchRepository {

    private static final String FROM = " FROM v_item_public";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ItemSearchRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count(ItemSearchCriteria criteria) {
        QueryParts query = queryParts(criteria);
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*)" + FROM + query.where(), query.parameters(), Long.class);
        return count == null ? 0L : count;
    }

    public List<ItemSearchRow> findPage(ItemSearchCriteria criteria) {
        QueryParts query = queryParts(criteria);
        String sql = "SELECT id, title, unit_code, level, tag_names" + FROM + query.where()
            + " ORDER BY " + criteria.orderBy()
            + " LIMIT " + ItemSearchCriteria.PAGE_SIZE + " OFFSET " + criteria.offset();
        return jdbcTemplate.query(sql, query.parameters(), (resultSet, rowNumber) -> {
            String tagNames = resultSet.getString("tag_names");
            List<String> tags = tagNames == null ? List.of() : Arrays.asList(tagNames.split(",", -1));
            return new ItemSearchRow(
                resultSet.getObject("id", Integer.class),
                resultSet.getString("title"),
                resultSet.getString("unit_code"),
                resultSet.getObject("level", Integer.class),
                tags);
        });
    }

    private QueryParts queryParts(ItemSearchCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (criteria.keyword() != null) {
            where.append(" AND (title LIKE :like OR stem LIKE :like)");
            parameters.addValue("like", "%" + criteria.keyword() + "%");
        }
        if (criteria.unit() != null) {
            where.append(" AND unit_code = :unit");
            parameters.addValue("unit", criteria.unit());
        }
        if (criteria.level() == null) {
            where.append(" AND level < 5");
        } else {
            where.append(" AND level = :level");
            parameters.addValue("level", criteria.level());
        }
        if (criteria.tag() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
                + " WHERE it.item_id = v_item_public.id AND t.name = :tag)");
            parameters.addValue("tag", criteria.tag());
        }
        return new QueryParts(where.toString(), parameters);
    }

    private record QueryParts(String where, MapSqlParameterSource parameters) {
    }
}
