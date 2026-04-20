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
    private final float BETA = 10.0f;  // Same as IndexerService
    
    // Main search method with TF-IDF and Cosine Similarity
// Change return type from Map<Integer, Integer> to Map<Integer, Double>
public Map<Integer, Double> search(String query, int limit) {
    if (query == null || query.isBlank()) {
        return Collections.emptyMap();
    }
    
    // Step 1: Build query vector with TF-IDF
    Map<String, Double> queryVector = buildTfIdfQueryVector(query);
    if (queryVector.isEmpty()) {
        return Collections.emptyMap();
    }
    
    // Step 2: Get total document count
    int totalDocuments = getTotalDocumentCount();
    if (totalDocuments == 0) {
        return Collections.emptyMap();
    }
    
    // Step 3: Get candidate pages
    Set<String> queryTerms = queryVector.keySet();
    String placeholders = String.join(",", Collections.nCopies(queryTerms.size(), "?"));
    
    String candidateSql = 
        "SELECT DISTINCT p.id " +
        "FROM pages p " +
        "JOIN keywords k ON p.id = k.page_id " +
        "JOIN words w ON k.word_id = w.id " +
        "WHERE w.word IN (" + placeholders + ")";
    
    List<Object> params = new ArrayList<>(queryTerms);
    List<Integer> candidatePages = db.query(candidateSql,
        (rs, rowNum) -> rs.getInt("id"),
        params.toArray()
    );
    
    if (candidatePages.isEmpty()) {
        return Collections.emptyMap();
    }
    
    // Step 4: Get term frequencies
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
        
        docTfVectors.computeIfAbsent(pageId, k -> new HashMap<>())
                    .put(word, tf);
    }, tfParams.toArray());
    
    // Step 5: Calculate TF-IDF for document vectors
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
    
    // Step 6: Calculate cosine similarity
    Map<Integer, Double> similarityScores = new HashMap<>();
    
    for (int pageId : candidatePages) {
        Map<String, Double> docVector = docTfIdfVectors.getOrDefault(pageId, new HashMap<>());
        double similarity = cosineSimilarity(queryVector, docVector);
        if (similarity > 0) {
            // Scale similarity to 0-100 and round to 2 decimal places
            double scaledScore = Math.round(similarity * 10000.0) / 100.0;
            similarityScores.put(pageId, scaledScore);
        }
    }
    
    // Step 7: Sort by similarity (highest first)
    List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(similarityScores.entrySet());
    sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
    
    // Return top N results with their actual similarity scores
    Map<Integer, Double> results = new LinkedHashMap<>();
    for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
        results.put(sorted.get(i).getKey(), sorted.get(i).getValue());
    }
    
    System.out.println("=== Cosine Similarity Search ===");
    System.out.println("Query: " + query);
    System.out.println("Found " + results.size() + " results");
    for (Map.Entry<Integer, Double> entry : results.entrySet()) {
        System.out.printf("  Page %d: Score = %.2f%n", entry.getKey(), entry.getValue());
    }
    
    return results;
}
    
    // Build query vector with TF-IDF
    private Map<String, Double> buildTfIdfQueryVector(String query) {
        String[] words = query.toLowerCase().split("\\s+");
        
        // Count term frequencies in query
        Map<String, Integer> queryTf = new HashMap<>();
        for (String word : words) {
            String processed = IndexerService.removeTrailingPunctuation(word);
            if (!processed.isEmpty()) {
                String stemmed = stemmer.stripAffixes(processed);
                if (!stemmed.isEmpty()) {
                    queryTf.put(stemmed, queryTf.getOrDefault(stemmed, 0) + 1);
                }
            }
        }
        
        int totalDocuments = getTotalDocumentCount();
        
        // Build TF-IDF vector
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
        
        // Normalize query vector
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
    
    // Cosine similarity calculation
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
    
    // Get total number of indexed pages
    private int getTotalDocumentCount() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
        return count == null ? 0 : count;
    }
    
    // Get document frequency for a term
    private int getDocumentFrequency(String term) {
        String sql = "SELECT COUNT(DISTINCT k.page_id) FROM keywords k " +
                     "JOIN words w ON k.word_id = w.id WHERE w.word = ?";
        Integer freq = db.queryForObject(sql, Integer.class, term);
        return freq == null ? 0 : freq;
    }
    
    // Exact phrase match filter (kept from your groupmate)
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

    // Preserve original similarity scores for exact matches
    Map<Integer, Double> exactMatches = new LinkedHashMap<>();
    for (Integer pageId : pageIds) {
        if (matchingIds.contains(pageId)) {
            exactMatches.put(pageId, ranking.get(pageId));
        }
    }

    return exactMatches;
}

    public List<Page> getPages(Map<Integer, Double> ranking) {
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

    // 2. Top 5 keywords per page
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

    // Build doubled params array
    Object[] doubleIds = new Object[n * 2];
    System.arraycopy(idsArray, 0, doubleIds, 0, n);
    System.arraycopy(idsArray, 0, doubleIds, n, n);

    // 3. Child pages
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

    // 4. Parent pages
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

    // 5. Assemble in ranking order (using similarity score, not rank position)
    List<Page> pages = new ArrayList<>();
    for (Integer pageId : pageIds) {
        String[] meta = metaMap.get(pageId);
        if (meta == null) continue;
        
        // Get the similarity score (0-100) from the ranking map
        Double similarityScore = ranking.get(pageId);
        int score = similarityScore != null ? similarityScore.intValue() : 0;
        
        pages.add(new Page(
            meta[0],  // url
            meta[1],  // title
            meta[2],  // last_modify_time
            meta[3] != null ? Integer.parseInt(meta[3]) : 0,  // content_size
            keywordsMap.getOrDefault(pageId, Collections.emptyMap()),
            childMap.getOrDefault(pageId, Collections.emptyList()),
            parentMap.getOrDefault(pageId, Collections.emptyList()),
            score  // Now this is the actual similarity score (0-100), not rank position!
        ));
    }

    return pages;
}
}