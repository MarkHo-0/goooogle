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
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS pages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT,
                last_modify_time TEXT,
                content_size INTEGER
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_pages_url
            ON pages (url)
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_words_word
            ON words (word)
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS keywords (
                page_id INTEGER NOT NULL,
                word_id INTEGER NOT NULL,
                body_count TINYINT NOT NULL DEFAULT 0,
                title_count TINYINT NOT NULL DEFAULT 0,
                weighted_count FLOAT NOT NULL DEFAULT 0,
                PRIMARY KEY (page_id, word_id),
                FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE,
                FOREIGN KEY (word_id) REFERENCES words(id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_keywords_word_id
            ON keywords (word_id)
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS links (
                parent_page_id INTEGER NOT NULL,
                child_page_id INTEGER NOT NULL,
                PRIMARY KEY (parent_page_id, child_page_id),
                FOREIGN KEY (parent_page_id) REFERENCES pages(id) ON DELETE CASCADE,
                FOREIGN KEY (child_page_id) REFERENCES pages(id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_links_child_page_id
            ON links (child_page_id)
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS pending_links (
                page_id INTEGER NOT NULL,
                outbound_link TEXT NOT NULL,
                PRIMARY KEY (page_id, outbound_link),
                FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE
            )
            """);

        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_links_outbound_link
            ON pending_links (outbound_link)
            """);
    }
}
