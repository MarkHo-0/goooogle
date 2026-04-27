package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.Word;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class KeywordService {
    @Autowired
    private JdbcTemplate db;

    @LoadSql("sql/list_words.sql")
    private String sqlFile_ListWords;

    public List<Word> listKeywords(String q, String sort, int limit, int offset, int page_count) {
        String query = (q == null || q.isEmpty()) ? "%" : "%" + q + "%";
        String sql = buildQuery(sort, limit, offset);
        
        try {
            return db.query(sql, Word.sqlMapper, query, page_count);
        } catch (Exception e) {
            System.out.println("Failed to list keywords: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildQuery(String sort, int limit, int offset) {
        String orderBy = "name".equals(sort) ? "ORDER BY t.word ASC" : "ORDER BY total_count DESC";
        return sqlFile_ListWords + "\n" + orderBy + "\nLIMIT " + limit + " OFFSET " + offset;
    }

    private static final String SQL_COUNT_WORDS = "SELECT COUNT(*) FROM words WHERE word LIKE ?";
    public int getTotalWordCount(String q) {
        String query = (q == null || q.isEmpty()) ? "%" : "%" + q + "%";
        try {
            Integer count = db.queryForObject(SQL_COUNT_WORDS, Integer.class, query);
            return count != null ? count : 0;
        } catch (Exception e) {
            System.out.println("Failed to get total word count: " + e.getMessage());
            return 0;
        }
    }
}