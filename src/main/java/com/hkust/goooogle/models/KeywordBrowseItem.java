package com.hkust.goooogle.models;

import org.springframework.jdbc.core.RowMapper;

public record KeywordBrowseItem(
    String word,
    int pageCount,
    int totalOccurrences,
    double totalWeight
) {
    public static final RowMapper<KeywordBrowseItem> sqlMapper = (rs, rowNum) -> new KeywordBrowseItem(
        rs.getString("word"),
        rs.getInt("page_count"),
        rs.getInt("total_occurrences"),
        rs.getDouble("total_weight")
    );
}