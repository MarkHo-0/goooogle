package com.hkust.goooogle.models;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record Page(String url, String title, String lastModifyTime, int contentSize, Map<String, Integer> keywordsWithCounts, List<Page> childPages, List<Page> parentPages, int score) {

    public String toMultiLineString(boolean showKeywords, boolean showLinkedPages) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(title).append("\n");
        sb.append(url).append("\n");
        sb.append(lastModifyTime).append(", ").append(contentSize).append(" bytes\n");

        if (showKeywords && !keywordsWithCounts.isEmpty()) {
            String keywordsStr = keywordsWithCounts.entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
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