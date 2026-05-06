package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.CandidatePage;
import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.models.PageKeyword;
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
        
        // 從查詢中提取關鍵字，每個 QueryKeyword 包含詞已經計算好的 TF-IDF 值
        List<QueryKeyword> queryKeywords = extractKeywordsFromQuery(query, directSearch);
        if (queryKeywords.isEmpty()) return Collections.emptyList(); // 查詢全部是停用詞或無效詞，直接返回空結果

        // 查詢符合的頁面ID列表和各自的關鍵字權重
        String keywordsStr = IntListToString(queryKeywords.stream().map(QueryKeyword::id).toList());
        System.out.println("New search: [" + String.join(", ", queryKeywords.stream().map(QueryKeyword::word).toList()) + "]");
        List<CandidatePage> candidatePages = db.query(sqlFile_FindMatchingPages, CandidatePage.sqlMapper, keywordsStr);
        if (candidatePages.isEmpty()) return Collections.emptyList(); // 沒有任何頁面匹配，直接返回空結果

        // 計算每個候選頁面關鍵字的 TF-IDF 值
        candidatePages.parallelStream().forEach(page -> {
            for (int i = 0; i < page.matchedKeywords().size(); i++) {
                float tf = page.matchedKeywords().get(i).totalCount();
                float idf = queryKeywords.get(i).idf();
                page.setKeywordWeight(i, tf * idf);
            }
        });

        if (queryKeywords.size() > 1) { 
            // 正常情況：多於一個關鍵字，使用 cosine 直接計算相似度分數
            List<Float> queryVector = queryKeywords.stream().map(QueryKeyword::tfIdf).toList();
            candidatePages.forEach(page -> {
                float similarity = cosineSimilarity(queryVector, page.keywordWeights());
                page.setSimilarityScore(similarity);
            });

        } else {
            // 特例：只有一個關鍵字，無法計算 cosine，相似度分數直接使用該關鍵字的權重
            candidatePages.forEach(page -> page.setSimilarityScore(page.keywordWeights().get(0)));

            // 將分數正規化到 [0, 1] 範圍內
            float maxWeight = candidatePages.stream().map(CandidatePage::similarityScore).max(Float::compareTo).orElse(0f);
            if (maxWeight > 0) {
                candidatePages.forEach(p -> p.setSimilarityScore(p.similarityScore() / maxWeight));
            }
        }

        // 計算標題加權分數，並將其與相似度分數結合
        candidatePages.parallelStream().forEach(page -> {
            float title_bonus = 0f;
            for (int i = 0; i < page.matchedKeywords().size(); i++) {
                float tf = page.matchedKeywords().get(i).titleCount();
                float idf = queryKeywords.get(i).idf();
                title_bonus += tf * idf;
            }
            float bounded_bonus = (float) (1 / (1 + Math.exp(-1 * title_bonus)));
            float final_score = (page.similarityScore() + bounded_bonus) / 2;
            page.setSimilarityScore(final_score);
        });
        
        // 根據相似度分數排序，返回所有候選頁面ID和分數
        List<Rankable<Integer>> rankedPages = candidatePages.stream()
            .sorted(Comparator.comparing(CandidatePage::similarityScore).reversed())
            .map(p -> new Rankable<>(p.pageid(), p.similarityScore()))
            .toList();
        
        return rankedPages;
    }
    
    private List<QueryKeyword> extractKeywordsFromQuery(String query, boolean directSearch) {
        // 計算查詢中每個詞的詞頻
        Map<String, Long> queryTf = directSearch ? 
            indexerService.computeUnprocessedWordDistribution(query) : 
            indexerService.computeWordDistribution(query);
        if (queryTf.isEmpty()) return Collections.emptyList();

        // 查詢關鍵字的ID和文檔頻率
        String terms = StrListToString(new ArrayList<>(queryTf.keySet()));
        List<QueryKeyword> keywords = db.query(sqlFile_QueryWordsByIds, QueryKeyword.sqlMapper, terms);

        int totalDocuments = indexerService.getStats().getIndexedPageCount();
        keywords.parallelStream().forEach(k -> {
            float idf = (float) Math.log((double) totalDocuments / k.pageCount());
            k.setIdf(idf);
            k.setTfIdf(queryTf.getOrDefault(k.word(), 0L) * idf);
        });

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
        sql.append(") AND full_page LIKE ? ESCAPE '\\'");

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