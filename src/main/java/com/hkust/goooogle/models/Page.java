package com.hkust.goooogle.models;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public record Page(
    String url, 
    String title, 
    String lastModifyTime, 
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
                rs.getString("last_modify_time"),
                rs.getInt("content_size"),
                JSON.readerForListOf(Keyword.class).readValue(rs.getString("top_N_Keywords")),
                JSON.readerForListOf(RefPage.class).readValue(rs.getString("child_pages")),
                JSON.readerForListOf(RefPage.class).readValue(rs.getString("parent_pages"))
            );
        } catch (Exception ex) {
            throw new SQLException("Failed to map row to Page", ex);
        }
    };

    public String topKeywordsAsQuery() {
        return topNKeywords.stream()
            .map(Keyword::word)
            .collect(Collectors.joining(" "));
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