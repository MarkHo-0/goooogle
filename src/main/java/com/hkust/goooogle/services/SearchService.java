package com.hkust.goooogle.services;

import com.hkust.goooogle.models.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {
    private final JdbcTemplate db;

    public SearchService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
    }

    public List<Page> search(String query, int limit) {
        final String sql = "SELECT url, title, last_modify_time, content_size FROM pages LIMIT ?";
        
        List<Page> results = db.query(sql, new Object[]{limit}, (rs, rowNum) -> 
            new Page(
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("last_modify_time"),
                rs.getInt("content_size"),
                new java.util.HashMap<>(),
                new ArrayList<>(),
                new ArrayList<>()
            )
        );
        
        return results;
    }
}