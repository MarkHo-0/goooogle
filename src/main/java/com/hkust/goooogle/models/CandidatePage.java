package com.hkust.goooogle.models;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

public class CandidatePage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int pageid;
    private final int maxTermCount;
    private final List<PageKeyword> matchedKeywords;
    private final List<Float> keywordWeights;
    private float similarityScore;

    public CandidatePage(int pageid, int maxTermCount, List<PageKeyword> matchedKeywords) {
        this.pageid = pageid;
        this.maxTermCount = maxTermCount;
        this.matchedKeywords = matchedKeywords;
        this.keywordWeights = new ArrayList<>(Collections.nCopies(matchedKeywords.size(), -1.0f));
        this.similarityScore = -1.0f;
    }

    public int pageid() {
        return pageid;
    }

    public int maxTermCount() {
        return maxTermCount;
    }

    public List<PageKeyword> matchedKeywords() {
        return matchedKeywords;
    }

    public float similarityScore() {
        return similarityScore;
    }

    public List<Float> keywordWeights() {
        return keywordWeights;
    }

    public void setKeywordWeight(int index, float weight) {
        this.keywordWeights.set(index, weight);
    }

    public void setSimilarityScore(float similarityScore) {
        this.similarityScore = similarityScore;
    }

    public static final RowMapper<CandidatePage> sqlMapper = (rs, rowNum) -> {
        try {
            return new CandidatePage(
                rs.getInt("page_id"),
                rs.getInt("max_term_count"),
                JSON.readerForListOf(PageKeyword.class).readValue(rs.getString("keyword_weights"))
            );
        } catch (Exception ex) {
            throw new SQLException("Failed to map row to CandidatePage", ex);
        }
    };
}
