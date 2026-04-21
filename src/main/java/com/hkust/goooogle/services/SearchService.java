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
    private final Set<String> stopWords;

    public SearchService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
        this.stopWords = loadStopWords();
    }

    private Set<String> loadStopWords() {
        try {
            org.springframework.core.io.ClassPathResource resource = 
                new org.springframework.core.io.ClassPathResource("stopwords.txt");
            String content = new String(resource.getInputStream().readAllBytes(), 
                java.nio.charset.StandardCharsets.UTF_8);
            Set<String> words = new HashSet<>();
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    words.add(trimmed);
                }
            }
            return words;
        } catch (Exception e) {
            System.err.println("Failed to load stop words: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    // Main search method
    public Map<Integer, Float> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        
        // Build query vector (keep stop words in queries!)
        Map<String, Float> queryVector = buildQueryVector(query);
        if (queryVector.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Get total document count
        int totalDocuments = getTotalDocumentCount();
        if (totalDocuments == 0) {
            return Collections.emptyMap();
        }
        
        // Get candidate pages with their weighted_count directly from DB
        Set<String> queryTerms = queryVector.keySet();
        String placeholders = String.join(",", Collections.nCopies(queryTerms.size(), "?"));
        
        // Single SQL to get pages and their weighted counts
        String sql = 
            "SELECT p.id, p.title, w.word, k.weighted_count " +
            "FROM pages p " +
            "JOIN keywords k ON p.id = k.page_id " +
            "JOIN words w ON k.word_id = w.id " +
            "WHERE w.word IN (" + placeholders + ")";
        
        List<Object> params = new ArrayList<>(queryTerms);
        
        // Store page data
        Map<Integer, String> pageTitles = new HashMap<>();
        Map<Integer, Map<String, Float>> pageWeightedCounts = new HashMap<>();
        Set<Integer> candidatePages = new HashSet<>();
        
        db.query(sql, (rs) -> {
            int pageId = rs.getInt("id");
            String title = rs.getString("title");
            String word = rs.getString("word");
            float weightedCount = rs.getFloat("weighted_count");
            
            candidatePages.add(pageId);
            pageTitles.put(pageId, title);
            pageWeightedCounts.computeIfAbsent(pageId, k -> new HashMap<>())
                              .put(word, weightedCount);
        }, params.toArray());
        
        if (candidatePages.isEmpty()) {
            System.out.println("No results found for: " + query);
            return Collections.emptyMap();
        }
        
        // Calculate similarity scores using weighted_count directly
        Map<Integer, Float> similarityScores = new HashMap<>();
        boolean isSingleTermQuery = (queryVector.size() == 1);
        
        for (int pageId : candidatePages) {
            Map<String, Float> docWeightedCounts = pageWeightedCounts.getOrDefault(pageId, new HashMap<>());
            
            float similarity;
            if (isSingleTermQuery) {
                // Single term: use TF-IDF score
                String term = queryVector.keySet().iterator().next();
                float weightedCount = docWeightedCounts.getOrDefault(term, 0f);
                int docFreq = getDocumentFrequency(term);
                float idf = (float) Math.log((double) totalDocuments / docFreq);
                similarity = weightedCount * idf;
                // Scale to 0-100
                similarity = Math.min(100, similarity * 5f);
            } else {
                // Multi-term: use cosine similarity with pre-calculated weighted_count
                similarity = cosineSimilarity(queryVector, docWeightedCounts, totalDocuments);
                similarity = Math.round(similarity * 10000f) / 100f;
            }
            
            if (similarity > 0) {
                similarityScores.put(pageId, similarity);
            }
        }
        
        // Sort and return top results
        List<Map.Entry<Integer, Float>> sorted = new ArrayList<>(similarityScores.entrySet());
        sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        
        Map<Integer, Float> results = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            results.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        }
        
        // Print ranking
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│  Search Results for: \"" + query + "\"");
        if (isSingleTermQuery) {
            System.out.println("│  Mode: Single-term (TF-IDF)");
        } else {
            System.out.println("│  Mode: Multi-term (Cosine Similarity)");
        }
        System.out.println("├─────────────────────────────────────────────────────────┤");
        
        int rank = 1;
        for (Map.Entry<Integer, Float> entry : results.entrySet()) {
            String title = pageTitles.get(entry.getKey());
            if (title == null) title = "Unknown";
            if (title.length() > 50) title = title.substring(0, 47) + "...";
            
            System.out.printf("│  %2d. %-40s │\n", rank, title);
            System.out.printf("│      Score: %.2f/100                          │\n", entry.getValue());
            rank++;
        }
        
        if (results.isEmpty()) {
            System.out.println("│  No results found.                                 │");
        }
        System.out.println("└─────────────────────────────────────────────────────────┘\n");
        
        return results;
    }
    
    // Build query vector (keep stop words - they are meaningful for search!)
    private Map<String, Float> buildQueryVector(String query) {
        String[] words = query.toLowerCase().split("\\s+");
        Map<String, Integer> queryTf = new HashMap<>();
        
        for (String word : words) {
            String processed = IndexerService.removeTrailingPunctuation(word);
            if (!processed.isEmpty()) {
                // Keep stop words in queries - they are meaningful!
                String stemmed = stemmer.stripAffixes(processed);
                if (!stemmed.isEmpty()) {
                    queryTf.put(stemmed, queryTf.getOrDefault(stemmed, 0) + 1);
                }
            }
        }
        
        int totalDocuments = getTotalDocumentCount();
        Map<String, Float> queryTfIdf = new HashMap<>();
        
        for (Map.Entry<String, Integer> entry : queryTf.entrySet()) {
            String term = entry.getKey();
            int tf = entry.getValue();
            int docFreq = getDocumentFrequency(term);
            if (docFreq > 0) {
                float idf = (float) Math.log((double) totalDocuments / docFreq);
                queryTfIdf.put(term, tf * idf);
            }
        }
        
        // Normalize query vector
        float norm = 0f;
        for (float weight : queryTfIdf.values()) {
            norm += weight * weight;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (String term : queryTfIdf.keySet()) {
                queryTfIdf.put(term, queryTfIdf.get(term) / norm);
            }
        }
        
        return queryTfIdf;
    }
    
    // Cosine similarity using pre-calculated weighted_count from database
    private float cosineSimilarity(Map<String, Float> queryVector, 
                                    Map<String, Float> docWeightedCounts,
                                    int totalDocuments) {
        float dotProduct = 0f;
        float queryNorm = 0f;
        float docNorm = 0f;
        
        // Calculate dot product and query norm
        for (Map.Entry<String, Float> entry : queryVector.entrySet()) {
            String term = entry.getKey();
            float queryWeight = entry.getValue();
            float docWeightedCount = docWeightedCounts.getOrDefault(term, 0f);
            
            // Get IDF for this term
            int docFreq = getDocumentFrequency(term);
            float idf = 1f;
            if (docFreq > 0) {
                idf = (float) Math.log((double) totalDocuments / docFreq);
            }
            
            // Document TF-IDF = weighted_count * idf
            float docTfIdf = docWeightedCount * idf;
            
            dotProduct += queryWeight * docTfIdf;
            queryNorm += queryWeight * queryWeight;
        }
        queryNorm = (float) Math.sqrt(queryNorm);
        
        // Calculate document norm
        for (Map.Entry<String, Float> entry : docWeightedCounts.entrySet()) {
            String term = entry.getKey();
            if (queryVector.containsKey(term)) {
                int docFreq = getDocumentFrequency(term);
                float idf = 1f;
                if (docFreq > 0) {
                    idf = (float) Math.log((double) totalDocuments / docFreq);
                }
                float docTfIdf = entry.getValue() * idf;
                docNorm += docTfIdf * docTfIdf;
            }
        }
        docNorm = (float) Math.sqrt(docNorm);
        
        if (queryNorm == 0 || docNorm == 0) return 0f;
        return dotProduct / (queryNorm * docNorm);
    }
    
    private int getTotalDocumentCount() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
        return count == null ? 0 : count;
    }
    
    private int getDocumentFrequency(String term) {
        String sql = "SELECT COUNT(DISTINCT k.page_id) FROM keywords k " +
                     "JOIN words w ON k.word_id = w.id WHERE w.word = ?";
        Integer freq = db.queryForObject(sql, Integer.class, term);
        return freq == null ? 0 : freq;
    }
    
    public Map<Integer, Float> excludeNonExactMatch(Map<Integer, Float> ranking, String query) {
        if (ranking.isEmpty()) {
            return new LinkedHashMap<>();
        }

        List<Integer> pageIds = new ArrayList<>(ranking.keySet());

        // Normalise whitespace so "HKUST  CSE" matches the same as "HKUST CSE"
        String normalised = query.trim().toLowerCase().replaceAll("\\s+", " ");
        String escapedQuery = normalised
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        String likePattern = "%" + escapedQuery + "%";

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

        Map<Integer, Float> exactMatches = new LinkedHashMap<>();
        for (Integer pageId : pageIds) {
            if (matchingIds.contains(pageId)) {
                exactMatches.put(pageId, ranking.get(pageId));
            }
        }
        
        // Print exact match results
        if (!exactMatches.isEmpty()) {
            System.out.println("\n┌─────────────────────────────────────────────────────────┐");
            System.out.println("│  EXACT MATCH RESULTS for: \"" + query + "\"");
            System.out.println("├─────────────────────────────────────────────────────────┤");
            int rank = 1;
            for (Map.Entry<Integer, Float> entry : exactMatches.entrySet()) {
                String title = getPageTitle(entry.getKey());
                if (title == null) title = "Unknown";
                if (title.length() > 50) title = title.substring(0, 47) + "...";
                System.out.printf("│  %2d. %-40s │\n", rank, title);
                System.out.printf("│      Score: %.2f/100                          │\n", entry.getValue());
                rank++;
            }
            System.out.println("└─────────────────────────────────────────────────────────┘\n");
        }

        return exactMatches;
    }
    
    private String getPageTitle(int pageId) {
        try {
            return db.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, pageId);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Page> getPages(Map<Integer, Float> ranking) {
        if (ranking.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> pageIds = new ArrayList<>(ranking.keySet());
        int n = pageIds.size();
        Object[] idsArray = pageIds.toArray();
        String ph = String.join(",", Collections.nCopies(n, "?"));

        // Fetch page metadata
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

        // Top 5 keywords per page
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

        Object[] doubleIds = new Object[n * 2];
        System.arraycopy(idsArray, 0, doubleIds, 0, n);
        System.arraycopy(idsArray, 0, doubleIds, n, n);

        // Child pages
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

        // Parent pages
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

        // Assemble results
        List<Page> pages = new ArrayList<>();
        for (Integer pageId : pageIds) {
            String[] meta = metaMap.get(pageId);
            if (meta == null) continue;
            
            Float similarityScore = ranking.get(pageId);
            float score = similarityScore != null ? similarityScore : 0f;
            
            pages.add(new Page(
                meta[0], meta[1], meta[2],
                meta[3] != null ? Integer.parseInt(meta[3]) : 0,
                keywordsMap.getOrDefault(pageId, Collections.emptyMap()),
                childMap.getOrDefault(pageId, Collections.emptyList()),
                parentMap.getOrDefault(pageId, Collections.emptyList()),
                score
            ));
        }

        return pages;
    }
}