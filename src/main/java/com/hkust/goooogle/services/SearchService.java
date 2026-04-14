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
    public List<Page> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
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
            return Collections.emptyList();
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
        
        // Query and map to Page records
        return db.query(sql.toString(), 
            (rs, rowNum) -> {
                return new Page(
                    rs.getString("url"),
                    rs.getString("title"),
                    rs.getString("last_modify_time"),
                    rs.getInt("content_size"),
                    Collections.emptyMap(),  // keywordsWithCounts - empty for search results
                    Collections.emptyList(),  // childPages
                    Collections.emptyList()   // parentPages
                );
            },
            params.toArray()
        );
    }
}