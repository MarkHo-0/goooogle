package com.hkust.goooogle.models;

import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

public class CandidatePage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int pageid;
    private final List<Float> keywordWeights;
    private float similarityScore;

    public CandidatePage(int pageid, List<Float> keywordWeights, float similarityScore) {
        this.pageid = pageid;
        this.keywordWeights = keywordWeights;
        this.similarityScore = similarityScore;
    }

    public int pageid() {
        return pageid;
    }

    public List<Float> keywordWeights() {
        return keywordWeights;
    }

    public float similarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(float similarityScore) {
        this.similarityScore = similarityScore;
    }

    public static final RowMapper<CandidatePage> sqlMapper = (rs, rowNum) -> {
        try {
            return new CandidatePage(
                rs.getInt("page_id"),
                JSON.readerForListOf(Float.class).readValue(rs.getString("weighted_counts")),
                -1.0f
            );
        } catch (Exception ex) {
            throw new SQLException("Failed to map row to CandidatePage", ex);
        }
    };
}
