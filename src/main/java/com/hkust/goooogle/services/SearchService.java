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
    private final float BETA = 10.0f;
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
    public Map<Integer, Double> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        
        // Build query vector
        Map<String, Double> queryVector = buildTfIdfQueryVector(query);
        if (queryVector.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Get total document count
        int totalDocuments = getTotalDocumentCount();
        if (totalDocuments == 0) {
            return Collections.emptyMap();
        }
        
        // Get candidate pages (OR logic)
        Set<String> queryTerms = queryVector.keySet();
        String placeholders = String.join(",", Collections.nCopies(queryTerms.size(), "?"));
        
        String candidateSql = 
            "SELECT DISTINCT p.id, p.title " +
            "FROM pages p " +
            "JOIN keywords k ON p.id = k.page_id " +
            "JOIN words w ON k.word_id = w.id " +
            "WHERE w.word IN (" + placeholders + ")";
        
        List<Object> params = new ArrayList<>(queryTerms);
        
        // Store page titles for output
        Map<Integer, String> pageTitles = new HashMap<>();
        List<Integer> candidatePages = new ArrayList<>();
        
        db.query(candidateSql, (rs) -> {
            int id = rs.getInt("id");
            String title = rs.getString("title");
            candidatePages.add(id);
            pageTitles.put(id, title);
        }, params.toArray());
        
        if (candidatePages.isEmpty()) {
            System.out.println("No results found for: " + query);
            return Collections.emptyMap();
        }
        
        // Get term frequencies
        String tfSql = 
            "SELECT p.id, w.word, " +
            "       (k.title_count * ? + k.body_count) as tf_weight " +
            "FROM pages p " +
            "JOIN keywords k ON p.id = k.page_id " +
            "JOIN words w ON k.word_id = w.id " +
            "WHERE p.id IN (" + String.join(",", Collections.nCopies(candidatePages.size(), "?")) + ")";
        
        List<Object> tfParams = new ArrayList<>();
        tfParams.add(BETA);
        tfParams.addAll(candidatePages);
        
        Map<Integer, Map<String, Double>> docTfVectors = new HashMap<>();
        
        db.query(tfSql, (rs) -> {
            int pageId = rs.getInt("id");
            String word = rs.getString("word");
            double tf = rs.getDouble("tf_weight");
            docTfVectors.computeIfAbsent(pageId, k -> new HashMap<>()).put(word, tf);
        }, tfParams.toArray());
        
        // Calculate TF-IDF for documents
        Map<Integer, Map<String, Double>> docTfIdfVectors = new HashMap<>();
        
        for (int pageId : candidatePages) {
            Map<String, Double> docTf = docTfVectors.getOrDefault(pageId, new HashMap<>());
            Map<String, Double> docTfIdf = new HashMap<>();
            
            for (Map.Entry<String, Double> entry : docTf.entrySet()) {
                String term = entry.getKey();
                double tf = entry.getValue();
                int docFreq = getDocumentFrequency(term);
                if (docFreq > 0) {
                    double idf = Math.log((double) totalDocuments / docFreq);
                    docTfIdf.put(term, tf * idf);
                }
            }
            docTfIdfVectors.put(pageId, docTfIdf);
        }
        
        // Calculate similarity scores
        Map<Integer, Double> similarityScores = new HashMap<>();
        
        for (int pageId : candidatePages) {
            Map<String, Double> docVector = docTfIdfVectors.getOrDefault(pageId, new HashMap<>());
            double similarity = cosineSimilarity(queryVector, docVector);
            if (similarity > 0) {
                double scaledScore = Math.round(similarity * 10000.0) / 100.0;
                similarityScores.put(pageId, scaledScore);
            }
        }
        
        // Sort and return top results
        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(similarityScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        Map<Integer, Double> results = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            results.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        }
        
        // ALWAYS print ranking and similarity scores
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│  Search Results for: \"" + query + "\"");
        System.out.println("├─────────────────────────────────────────────────────────┤");
        
        int rank = 1;
        for (Map.Entry<Integer, Double> entry : results.entrySet()) {
            String title = pageTitles.get(entry.getKey());
            if (title == null) title = "Unknown";
            // Truncate long titles
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
    
    private Map<String, Double> buildTfIdfQueryVector(String query) {
        String[] words = query.toLowerCase().split("\\s+");
        Map<String, Integer> queryTf = new HashMap<>();
        
        for (String word : words) {
            String processed = IndexerService.removeTrailingPunctuation(word);
            if (!processed.isEmpty() && !stopWords.contains(processed)) {
                String stemmed = stemmer.stripAffixes(processed);
                if (!stemmed.isEmpty()) {
                    queryTf.put(stemmed, queryTf.getOrDefault(stemmed, 0) + 1);
                }
            }
        }
        
        int totalDocuments = getTotalDocumentCount();
        Map<String, Double> queryTfIdf = new HashMap<>();
        
        for (Map.Entry<String, Integer> entry : queryTf.entrySet()) {
            String term = entry.getKey();
            int tf = entry.getValue();
            int docFreq = getDocumentFrequency(term);
            if (docFreq > 0) {
                double idf = Math.log((double) totalDocuments / docFreq);
                queryTfIdf.put(term, tf * idf);
            }
        }
        
        // Normalize
        double norm = 0.0;
        for (double weight : queryTfIdf.values()) {
            norm += weight * weight;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (String term : queryTfIdf.keySet()) {
                queryTfIdf.put(term, queryTfIdf.get(term) / norm);
            }
        }
        
        return queryTfIdf;
    }
    
    private double cosineSimilarity(Map<String, Double> queryVector, Map<String, Double> docVector) {
        double dotProduct = 0.0;
        double queryNorm = 0.0;
        double docNorm = 0.0;
        
        for (Map.Entry<String, Double> entry : queryVector.entrySet()) {
            String term = entry.getKey();
            double queryWeight = entry.getValue();
            double docWeight = docVector.getOrDefault(term, 0.0);
            dotProduct += queryWeight * docWeight;
            queryNorm += queryWeight * queryWeight;
        }
        queryNorm = Math.sqrt(queryNorm);
        
        for (double weight : docVector.values()) {
            docNorm += weight * weight;
        }
        docNorm = Math.sqrt(docNorm);
        
        if (queryNorm == 0 || docNorm == 0) return 0.0;
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
    
    public Map<Integer, Double> excludeNonExactMatch(Map<Integer, Double> ranking, String query) {
        if (ranking.isEmpty()) {
            return new LinkedHashMap<>();
        }

        List<Integer> pageIds = new ArrayList<>(ranking.keySet());
        String escapedQuery = query.toLowerCase()
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

        Map<Integer, Double> exactMatches = new LinkedHashMap<>();
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
            for (Map.Entry<Integer, Double> entry : exactMatches.entrySet()) {
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

    public List<Page> getPages(Map<Integer, Double> ranking) {
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
            
            Double similarityScore = ranking.get(pageId);
            int score = similarityScore != null ? similarityScore.intValue() : 0;
            
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