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

    public List<Page> getPages(Map<Integer, Integer> ranking) {
        if (ranking.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> pageIds = new ArrayList<>(ranking.keySet());

        // 1. Fetch page metadata
        StringBuilder metaSql = new StringBuilder(
            "SELECT id, url, title, last_modify_time, content_size FROM pages WHERE id IN ("
        );
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) metaSql.append(",");
            metaSql.append("?");
        }
        metaSql.append(")");

        Map<Integer, String[]> metaMap = new HashMap<>(); // id -> [url, title, lastModify, contentSize]
        db.query(metaSql.toString(), (rs) -> {
            metaMap.put(rs.getInt("id"), new String[]{
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("last_modify_time"),
                rs.getString("content_size")
            });
        }, pageIds.toArray());

        // 2. Fetch top 5 keywords per page using ROW_NUMBER window function
        StringBuilder kwSql = new StringBuilder(
            "SELECT page_id, word, weighted_count FROM (" +
            "SELECT k.page_id, w.word, k.weighted_count, " +
            "ROW_NUMBER() OVER (PARTITION BY k.page_id ORDER BY k.weighted_count DESC) AS rn " +
            "FROM keywords k JOIN words w ON k.word_id = w.id WHERE k.page_id IN ("
        );
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) kwSql.append(",");
            kwSql.append("?");
        }
        kwSql.append(")) WHERE rn <= 5");

        Map<Integer, Map<String, Integer>> keywordsMap = new HashMap<>();
        db.query(kwSql.toString(), (rs) -> {
            int pid = rs.getInt("page_id");
            keywordsMap.computeIfAbsent(pid, k -> new LinkedHashMap<>())
                .put(rs.getString("word"), (int) rs.getFloat("weighted_count"));
        }, pageIds.toArray());

        // 3. Fetch up to 5 child pages per page (pages linked FROM this page)
        StringBuilder childSql = new StringBuilder(
            "SELECT l.parent_page_id, p.url FROM links l JOIN pages p ON l.child_page_id = p.id " +
            "WHERE l.parent_page_id IN ("
        );
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) childSql.append(",");
            childSql.append("?");
        }
        childSql.append(")");

        Map<Integer, List<Page>> childMap = new HashMap<>();
        db.query(childSql.toString(), (rs) -> {
            int pid = rs.getInt("parent_page_id");
            List<Page> list = childMap.computeIfAbsent(pid, k -> new ArrayList<>());
            if (list.size() < 5) {
                list.add(new Page(rs.getString("url"), null, null, 0,
                    Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), 0));
            }
        }, pageIds.toArray());

        // 4. Fetch up to 5 parent pages per page (pages that link TO this page)
        StringBuilder parentSql = new StringBuilder(
            "SELECT l.child_page_id, p.url FROM links l JOIN pages p ON l.parent_page_id = p.id " +
            "WHERE l.child_page_id IN ("
        );
        for (int i = 0; i < pageIds.size(); i++) {
            if (i > 0) parentSql.append(",");
            parentSql.append("?");
        }
        parentSql.append(")");

        Map<Integer, List<Page>> parentMap = new HashMap<>();
        db.query(parentSql.toString(), (rs) -> {
            int pid = rs.getInt("child_page_id");
            List<Page> list = parentMap.computeIfAbsent(pid, k -> new ArrayList<>());
            if (list.size() < 5) {
                list.add(new Page(rs.getString("url"), null, null, 0,
                    Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), 0));
            }
        }, pageIds.toArray());

        // 5. Assemble results in ranking order
        List<Page> pages = new ArrayList<>();
        for (Integer pageId : pageIds) {
            String[] meta = metaMap.get(pageId);
            if (meta == null) continue;
            pages.add(new Page(
                meta[0],
                meta[1],
                meta[2],
                meta[3] != null ? Integer.parseInt(meta[3]) : 0,
                keywordsMap.getOrDefault(pageId, Collections.emptyMap()),
                childMap.getOrDefault(pageId, Collections.emptyList()),
                parentMap.getOrDefault(pageId, Collections.emptyList()),
                ranking.get(pageId)
            ));
        }

        return pages;
    }
}