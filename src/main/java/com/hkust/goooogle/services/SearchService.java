package com.hkust.goooogle.services;

import com.hkust.goooogle.models.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import IRUtilities.Porter;
import java.util.*;

@Service
public class SearchService {

    @Autowired
    private JdbcTemplate db;
    
    private final Porter stemmer = new Porter();
    
    // Simple working version for your record
    public Map<Integer, Integer> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        
        // Process query - stem each word
        String[] keywords = query.toLowerCase().split("\\s+");
        Set<String> stemmedKeywords = new HashSet<>();
        for (String keyword : keywords) {
            String processed = IndexerService.removeTrailingPunctuation(keyword);
            if (!processed.isEmpty()) {
                String stemmed = stemmer.stripAffixes(processed);
                if (!stemmed.isEmpty()) {
                    stemmedKeywords.add(stemmed);
                }
            }
        }
        
        if (stemmedKeywords.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Build SQL query
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT p.id, p.url, p.title, p.last_modify_time, p.content_size " +
            "FROM pages p " +
            "JOIN keywords k ON p.id = k.page_id " +
            "JOIN words w ON k.word_id = w.id WHERE "
        );
        
        List<Object> params = new ArrayList<>();
        int i = 0;
        for (String keyword : stemmedKeywords) {
            if (i++ > 0) sql.append(" OR ");
            sql.append("w.word = ?");
            params.add(keyword);
        }
        
        sql.append(" LIMIT ?");
        params.add(limit);
        
        // Query and map to Map<Integer, Integer> with page ID and score
        Map<Integer, Integer> results = new LinkedHashMap<>();
        int score = limit;
        
        List<Integer> pageIds = db.query(sql.toString(),
            (rs, rowNum) -> rs.getInt("id"),
            params.toArray()
        );
        
        for (Integer pageId : pageIds) {
            results.put(pageId, score--);
        }
        
        return results;
    }
        
    public Map<Integer, Integer> excludeNonExactMatch(Map<Integer, Integer> ranking, String query) {
        if (ranking.isEmpty()) {
            return new LinkedHashMap<>();
        }

        List<Integer> pageIds = new ArrayList<>(ranking.keySet());

        // Escape LIKE special characters so the phrase is matched literally
        String escapedQuery = query.toLowerCase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        String likePattern = "%" + escapedQuery + "%";

        // Push filtering into SQL: only fetch IDs of matching pages,
        // avoiding transferring large full_page text blobs to Java.
        StringBuilder sql = new StringBuilder("SELECT id FROM pages WHERE id IN (");
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(") AND LOWER(full_page) LIKE ? ESCAPE '\\'");

        List<Object> params = new ArrayList<>(pageIds);
        params.add(likePattern);

        Set<Integer> matchingIds = new HashSet<>(db.query(sql.toString(),
            (rs, rowNum) -> rs.getInt("id"),
            params.toArray()
        ));

        Map<Integer, Integer> exactMatches = new LinkedHashMap<>();
        for (Integer pageId : pageIds) {
            if (matchingIds.contains(pageId)) {
                exactMatches.put(pageId, ranking.get(pageId));
            }
        }

        return exactMatches;
    }
}