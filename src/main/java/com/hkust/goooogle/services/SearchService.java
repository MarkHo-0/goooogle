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
            "SELECT DISTINCT p.id " +
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
        int n = pageIds.size();
        Object[] idsArray = pageIds.toArray();
        String ph = String.join(",", Collections.nCopies(n, "?"));

        // 1. Fetch page metadata
        Map<Integer, String[]> metaMap = new HashMap<>();
        db.query(
            "SELECT id, url, title, last_modify_time, content_size FROM pages WHERE id IN (" + ph + ")",
            (rs) -> {
                metaMap.put(rs.getInt("id"), new String[]{
                    rs.getString("url"),
                    rs.getString("title"),
                    rs.getString("last_modify_time"),
                    rs.getString("content_size")
                });
            }, idsArray);

        // 2. Top 5 keywords per page via ROW_NUMBER window function
        Map<Integer, Map<String, Integer>> keywordsMap = new HashMap<>();
        db.query(
            "SELECT page_id, word, weighted_count FROM (" +
            "SELECT k.page_id, w.word, k.weighted_count, " +
            "ROW_NUMBER() OVER (PARTITION BY k.page_id ORDER BY k.weighted_count DESC) AS rn " +
            "FROM keywords k JOIN words w ON k.word_id = w.id WHERE k.page_id IN (" + ph + ")" +
            ") WHERE rn <= 5",
            (rs) -> {
                keywordsMap
                    .computeIfAbsent(rs.getInt("page_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("word"), (int) rs.getFloat("weighted_count"));
            }, idsArray);

        // Build doubled params array (used for both UNION branches)
        Object[] doubleIds = new Object[n * 2];
        System.arraycopy(idsArray, 0, doubleIds, 0, n);
        System.arraycopy(idsArray, 0, doubleIds, n, n);

        // 3. Child pages: indexed (links → pages) UNION ALL pending (pending_links)
        //    Indexed rows come first so they fill slots before pending ones.
        Map<Integer, List<Page>> childMap = new HashMap<>();
        db.query(
            "SELECT pid, url, title, is_pending FROM (" +
            "SELECT l.parent_page_id AS pid, p.url AS url, p.title AS title, 0 AS is_pending " +
            "  FROM links l JOIN pages p ON l.child_page_id = p.id WHERE l.parent_page_id IN (" + ph + ") " +
            "UNION ALL " +
            "SELECT page_id AS pid, outbound_link AS url, NULL AS title, 1 AS is_pending " +
            "  FROM pending_links WHERE page_id IN (" + ph + ")" +
            ")",
            (rs) -> {
                int pid = rs.getInt("pid");
                List<Page> list = childMap.computeIfAbsent(pid, k -> new ArrayList<>());
                if (list.size() < 5) {
                    String title = rs.getInt("is_pending") == 1 ? "Page not indexed yet" : rs.getString("title");
                    list.add(new Page(rs.getString("url"), title, null, 0,
                        Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), 0));
                }
            }, doubleIds);

        // 4. Parent pages: indexed (links → pages) UNION ALL pending
        //    For pending parents: join pages twice — once to get the parent URL (pl.page_id → pages),
        //    once to resolve the target result page ID (pl.outbound_link = pages.url → pages.id).
        //    This avoids needing a urlToId map in Java.
        Map<Integer, List<Page>> parentMap = new HashMap<>();
        db.query(
            "SELECT pid, url, title, is_pending FROM (" +
            "SELECT l.child_page_id AS pid, p.url AS url, p.title AS title, 0 AS is_pending " +
            "  FROM links l JOIN pages p ON l.parent_page_id = p.id WHERE l.child_page_id IN (" + ph + ") " +
            "UNION ALL " +
            "SELECT p2.id AS pid, p.url AS url, NULL AS title, 1 AS is_pending " +
            "  FROM pending_links pl " +
            "  JOIN pages p  ON pl.page_id      = p.id " +
            "  JOIN pages p2 ON pl.outbound_link = p2.url " +
            "  WHERE p2.id IN (" + ph + ")" +
            ")",
            (rs) -> {
                int pid = rs.getInt("pid");
                List<Page> list = parentMap.computeIfAbsent(pid, k -> new ArrayList<>());
                if (list.size() < 5) {
                    String title = rs.getInt("is_pending") == 1 ? "Page not indexed yet" : rs.getString("title");
                    list.add(new Page(rs.getString("url"), title, null, 0,
                        Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), 0));
                }
            }, doubleIds);

        // 5. Assemble in ranking order
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