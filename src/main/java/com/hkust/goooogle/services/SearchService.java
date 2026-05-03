package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.CandidatePage;
import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.models.QueryKeyword;
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

    @LoadSql("sql/query_words_by_ids.sql")
    private String sqlFile_QueryWordsByIds;

    @LoadSql("sql/find_matching_pages.sql")
    private String sqlFile_FindMatchingPages;

    // directSearch: 不對 query 進行任何處理，用於關鍵詞精確匹配的搜尋
    // 但這不同於 exactMatch (是在最後過濾階段對全文進行匹配)
    public List<Rankable<Integer>> search(String query, boolean directSearch) {
        if (query.isBlank()) return Collections.emptyList(); // 空查詢直接返回空結果

        int totalDocuments = indexerService.getStats().getIndexedPageCount();
        if (totalDocuments == 0) return Collections.emptyList(); // Spider 沒有索引過任何頁面，直接返回空結果
        
        // 構建查詢向量，包含每個關鍵字的正規化IDF值
        List<QueryKeyword> queryKeywords = buildQueryVector(query, directSearch);
        if (queryKeywords.isEmpty()) return Collections.emptyList(); // 查詢全部是停用詞或無效詞，直接返回空結果

        // 查詢符合的頁面ID列表和各自的關鍵字權重
        String keywordsStr = IntListToString(queryKeywords.stream().map(QueryKeyword::id).toList());
        List<CandidatePage> candidatePages = db.query(sqlFile_FindMatchingPages, CandidatePage.sqlMapper, keywordsStr);
        
        System.out.println("Found " + candidatePages.size() + " candidate pages for query: " + query);

        if (queryKeywords.size() != 1) { 
            // 正常情況：多於一個關鍵字，使用 cosine 直接計算相似度分數
            candidatePages.forEach(p -> {
                List<Float> queryVector = queryKeywords.stream().map(QueryKeyword::idf).toList();
                List<Float> docVector = p.keywordWeights();
                p.setSimilarityScore(cosineSimilarity(queryVector, docVector) * 100);
            }); 
        } else {
            // 特例：只有一個關鍵字，無法計算 cosine，相似度分數直接使用 weighted_count * IDF 
            int docFreq = queryKeywords.get(0).pageCount();
            float idf = (float) Math.log((double) totalDocuments / docFreq);

            candidatePages.forEach(p -> {
                float weightedCount = p.keywordWeights().getFirst();
                p.setSimilarityScore(weightedCount * idf);
            });

            // 標準化，使最高的對應到100分
            float maxWeight = candidatePages.stream().map(CandidatePage::similarityScore).max(Float::compareTo).orElse(0f);
            if (maxWeight > 0) {
                candidatePages.forEach(p -> p.setSimilarityScore(p.similarityScore() / maxWeight * 100));
            }
        }
        
        // 根據相似度分數排序，返回所有候選頁面ID和分數
        List<Rankable<Integer>> rankedPages = candidatePages.stream()
            .sorted(Comparator.comparing(CandidatePage::similarityScore).reversed())
            .map(p -> new Rankable<>(p.pageid(), p.similarityScore()))
            .toList();
        
        return rankedPages;
    }
    
    private List<QueryKeyword> buildQueryVector(String query, boolean directSearch) {
        // 計算查詢中每個詞的詞頻
        Map<String, Long> queryTf = directSearch ? 
            indexerService.computeUnprocessedWordDistribution(query) : 
            indexerService.computeWordDistribution(query);
        if (queryTf.isEmpty()) return Collections.emptyList();

        // 查詢關鍵字的ID和文檔頻率
        String terms = StrListToString(new ArrayList<>(queryTf.keySet()));
        List<QueryKeyword> keywords = db.query(sqlFile_QueryWordsByIds, QueryKeyword.sqlMapper, terms);

        // 計算IDF值
        int totalDocuments = indexerService.getStats().getIndexedPageCount();
        keywords.forEach(k -> k.setIdf((float) Math.log((double) totalDocuments / k.pageCount())));

        // 正規化IDF值，使其在0到1之間
        double norm = Math.sqrt(keywords.stream().mapToDouble(k -> k.idf() * k.idf()).sum());
        if (norm > 0) {
            keywords.forEach(k -> k.setIdf(k.idf() / (float) norm));
        }

        return keywords;
    }
    
    private float cosineSimilarity(List<Float> queryVector, List<Float> docVector) {
        int size = Math.min(queryVector.size(), docVector.size());
        if (size == 0) return 0f;

        double dotProduct = 0.0;
        double queryNorm = 0.0;
        double docNorm = 0.0;

        for (int i = 0; i < size; i++) {
            double q = queryVector.get(i);
            double d = docVector.get(i);
            dotProduct += q * d;
            queryNorm += q * q;
            docNorm += d * d;
        }

        if (queryNorm == 0.0 || docNorm == 0.0) return 0f;
        return (float) (dotProduct / (Math.sqrt(queryNorm) * Math.sqrt(docNorm)));
    }
    
    public List<Rankable<Integer>> excludeNonExactMatch(List<Rankable<Integer>> ranking, String query) {
        if (ranking.isEmpty()) return new ArrayList<>();

        List<Integer> pageIds = new ArrayList<>(ranking.stream().map(Rankable::item).toList());

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

        // 只保留那些頁面ID在 matchingIds 中的結果，並保持原有的排名順序
        List<Rankable<Integer>> exactMatches = ranking.stream()
            .filter(r -> matchingIds.contains(r.item()))
            .toList();

        return exactMatches;
    }

    @LoadSql("sql/get_pages_by_ids.sql")
    private String sqlFile_GetPagesByIds;
    
    public List<Rankable<Page>> getPages(List<Rankable<Integer>> ranking) {
        if (ranking.isEmpty()) {
            return Collections.emptyList();
        }

        String pageIds = IntListToString(ranking.stream().map(Rankable::item).toList());

        try {
            List<Page> pages = db.query(sqlFile_GetPagesByIds, Page.sqlMapper, pageIds, 5);
            Iterator<Double> scores = ranking.stream().map(Rankable::similarityScore).iterator();
            return pages.stream().map(page -> new Rankable<>(page, scores.next())).toList();
        } catch (Exception e) {
            System.out.println("Failed to fetch page details: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String IntListToString(List<Integer> keys) {
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

    private static String StrListToString(List<String> keys) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(",");
            sb.append(String.format("\"%s\"", key));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
}