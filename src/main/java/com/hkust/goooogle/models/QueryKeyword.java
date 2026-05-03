package com.hkust.goooogle.models;

import org.springframework.jdbc.core.RowMapper;

public class QueryKeyword {

    private final String word;
    private final int id;
    private final int pageCount;
    private float idf;

    public QueryKeyword(String word, int id, int pageCount) {
        this.word = word;
        this.id = id;
        this.pageCount = pageCount;
        this.idf = -1.0f; // Placeholder, to be computed later
    }

    public final static RowMapper<QueryKeyword> sqlMapper = (rs, rowNum) -> new QueryKeyword(
        rs.getString("word"),
        rs.getInt("word_id"),
        rs.getInt("page_count")
    );

    public String word() {
        return word;
    }

    public int id() {
        return id;
    }

    public int pageCount() {
        return pageCount;
    }

    public float idf() {
        return idf;
    }

    public void setIdf(float idf) {
        this.idf = idf;
    }
}
