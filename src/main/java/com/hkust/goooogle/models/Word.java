package com.hkust.goooogle.models;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.util.List;

public record Word(
    String word,
    int totalCount,
    List<RefPage> pages
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final RowMapper<Word> sqlMapper = (rs, rowNum) -> {
        try {
            String jsonPages = rs.getString("pages");
            List<RefPage> pages = jsonPages != null && !jsonPages.isEmpty()
                ? JSON.readerForListOf(RefPage.class).readValue(jsonPages)
                : List.of();
            
            return new Word(
                rs.getString("word"),
                rs.getInt("total_count"),
                pages
            );
        } catch (Exception ex) {
            throw new SQLException("Failed to map row to Word", ex);
        }
    };
}
