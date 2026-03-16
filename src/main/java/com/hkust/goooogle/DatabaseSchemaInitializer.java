package com.hkust.goooogle;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS pages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                last_modified DATETIME
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_pages_url
            ON pages (url)
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_words_word
            ON words (word)
            """);
    }
}
