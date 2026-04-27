package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.models.Rankable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SearchService {
    @Autowired
    private JdbcTemplate db;
    private final IndexerService indexerService;

    public SearchService(JdbcTemplate jdbcTemplate, IndexerService indexerService) {
        this.db = jdbcTemplate;
        this.indexerService = indexerService;
    }

    // Main search method
    public Map<Integer, Float> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        
        // Build query vector
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
        
        String sql = 
            "SELECT p.id, w.word, k.weighted_count " +
            "FROM pages p " +
            "JOIN keywords k ON p.id = k.page_id " +
            "JOIN words w ON k.word_id = w.id " +
            "WHERE w.word IN (" + placeholders + ")";
        
        List<Object> params = new ArrayList<>(queryTerms);
        
        // Store page weighted counts
        Map<Integer, Map<String, Float>> pageWeightedCounts = new HashMap<>();
        Set<Integer> candidatePages = new HashSet<>();
        
        db.query(sql, (rs) -> {
            int pageId = rs.getInt("id");
            String word = rs.getString("word");
            float weightedCount = rs.getFloat("weighted_count");
            
            candidatePages.add(pageId);
            pageWeightedCounts.computeIfAbsent(pageId, k -> new HashMap<>())
                              .put(word, weightedCount);
        }, params.toArray());
        
        if (candidatePages.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Calculate similarity scores
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
                similarity = Math.min(100, weightedCount * idf * 5f);
            } else {
                // Multi-term: use cosine similarity
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
        
        return results;
    }
    
    // Build query vector using IndexerService's computeWordDistribution
    private Map<String, Float> buildQueryVector(String query) {
        Map<String, Long> queryTf = indexerService.computeWordDistribution(query);
        
        int totalDocuments = getTotalDocumentCount();
        Map<String, Float> queryTfIdf = new HashMap<>();
        
        for (Map.Entry<String, Long> entry : queryTf.entrySet()) {
            String term = entry.getKey();
            long tf = entry.getValue();
            int docFreq = getDocumentFrequency(term);
            if (docFreq > 0) {
                float idf = (float) Math.log((double) totalDocuments / docFreq);
                queryTfIdf.put(term, (float) tf * idf);
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
            
            int docFreq = getDocumentFrequency(term);
            float idf = (docFreq > 0) ? (float) Math.log((double) totalDocuments / docFreq) : 1f;
            
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
                float idf = (docFreq > 0) ? (float) Math.log((double) totalDocuments / docFreq) : 1f;
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

        return exactMatches;
    }

    @LoadSql("sql/get_pages_by_ids.sql")
    private String sqlFile_GetPagesByIds;
    
    public List<Rankable<Page>> getPages(Map<Integer, Float> ranking) {
        if (ranking.isEmpty()) {
            return Collections.emptyList();
        }

        String pageIds = mapKeysToString(ranking.keySet());

        try {
            List<Page> pages = db.query(sqlFile_GetPagesByIds, Page.sqlMapper, pageIds, 5);
            Iterator<Float> scores = ranking.values().iterator();
            return pages.stream().map(page -> new Rankable<>(page, scores.next())).toList();
        } catch (Exception e) {
            System.out.println("Failed to fetch page details: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String mapKeysToString(Set<Integer> keys) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Integer id : keys) {
            if (!first) sb.append(",");
            sb.append(id);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
}