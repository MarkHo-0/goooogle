package com.hkust.goooogle.models;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public record Page(
    String url, 
    String title, 
    ZonedDateTime lastModifyTime, 
    int contentSizeInBytes, 
    List<Keyword> topNKeywords,
    List<RefPage> childPages, 
    List<RefPage> parentPages
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final RowMapper<Page> sqlMapper = (rs, rowNum) -> {
        try {
            return new Page(
                rs.getString("url"),
                rs.getString("title"),
                ZonedDateTime.parse(rs.getString("last_modify_time"), DateTimeFormatter.RFC_1123_DATE_TIME),
                rs.getInt("content_size"),
                JSON.readerForListOf(Keyword.class).readValue(rs.getString("top_N_Keywords")),
                JSON.readerForListOf(RefPage.class).readValue(rs.getString("child_pages")),
                JSON.readerForListOf(RefPage.class).readValue(rs.getString("parent_pages"))
            );
        } catch (Exception ex) {
            throw new SQLException("Failed to map row to Page", ex);
        }
    };

    public String buildSearchQueryForTopKeywords() {
        String kws = topNKeywords.stream().map(Keyword::word).collect(Collectors.joining("+"));
        return String.format("/search?q=%s&direct_search=true", kws);
    }

    public String toMultiLineString(boolean showKeywords, boolean showLinkedPages) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(title).append("\n");
        sb.append(url).append("\n");
        sb.append(lastModifyTime).append(", ").append(contentSizeInBytes).append(" bytes\n");

        if (showKeywords && !topNKeywords.isEmpty()) {
            String keywordsStr = topNKeywords.stream()
                .map(k -> k.word() + " (" + k.totalCount() + ")")
                .collect(Collectors.joining("; "));
            sb.append(keywordsStr).append("\n");
        }

        if (showLinkedPages && !parentPages.isEmpty()) {
            parentPages.forEach(p -> sb.append(p.url()).append("\n"));
        }

        if (showLinkedPages && !childPages.isEmpty()) {
            childPages.forEach(p -> sb.append(p.url()).append("\n"));
        }
    
        return sb.toString();
    }
}