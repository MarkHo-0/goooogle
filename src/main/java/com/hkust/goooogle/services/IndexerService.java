package com.hkust.goooogle.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IndexerService: Extracts words from cached HTML files and builds the inverted index.
 * 
 * Workflow:
 * 1. For each cached HTML file (cache_pages/{pageId}.html)
 * 2. Parse HTML with JSoup
 * 3. Extract words and their frequencies using getWordsFreq(Document)
 * 4. Insert unique words into "words" table
 * 5. Insert word-page relationships and frequencies into "keywords" table
 */
@Service
public class IndexerService {

    private final JdbcTemplate db;
    
    // Pattern to split text into words (letters, numbers, hyphens)
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]+");

    public IndexerService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
    }

    /**
     * Extract words and their frequencies from an HTML document.
     * 
     * Steps:
     * 1. Get plain text content from document
     * 2. Convert to lowercase
     * 3. Split into words using regex
     * 4. Count frequency of each word
     * 5. Filter out common stop words
     * 
     * @param doc JSoup Document object (parsed HTML)
     * @return Map of word → frequency count
     */
    public Map<String, Integer> getWordsFreq(Document doc) {
        // Extract text from HTML (ignores script/style tags)
        String text = doc.body().text();
        
        // Convert to lowercase for case-insensitive analysis
        String lowerText = text.toLowerCase();
        
        // Split into words using regex pattern: [a-z0-9]+
        Map<String, Integer> wordFreq = new HashMap<>();
        var matcher = WORD_PATTERN.matcher(lowerText);
        
        while (matcher.find()) {
            String word = matcher.group();
            
            // Skip very short words (noise)
            if (word.length() < 2) {
                continue;
            }
            
            // Skip common stop words
            if (isStopWord(word)) {
                continue;
            }
            
            // Increment count
            wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
        }
        
        return wordFreq;
    }

    /**
     * Common English stop words to filter out.
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "be", "been",
        "will", "would", "could", "should", "may", "might", "must", "can",
        "this", "that", "these", "those", "i", "you", "he", "she", "it",
        "we", "they", "what", "which", "who", "when", "where", "why", "how"
    );

    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    /**
     * Index a single page: extract words from cached HTML and store in database.
     * 
     * @param pageId ID of the page (matches cache_pages/{pageId}.html)
     * @return true if successful, false if page not found or error
     */
    public boolean indexPage(int pageId) {
        try {
            // Step 1: Load HTML from cache
            String html = loadHtmlFromCache(pageId);
            if (html == null) {
                System.err.println("Cache file not found for page ID: " + pageId);
                return false;
            }

            // Step 2: Parse HTML with JSoup
            Document doc = Jsoup.parse(html);

            // Step 3: Extract word frequencies
            Map<String, Integer> wordFreq = getWordsFreq(doc);

            // Step 4: Store words and keywords in database
            storeWordsAndKeywords(pageId, wordFreq);

            System.out.println("Indexed page " + pageId + " with " + wordFreq.size() + " unique words");
            return true;

        } catch (Exception e) {
            System.err.println("Error indexing page " + pageId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Index all cached pages in the cache_pages directory.
     * 
     * @return Number of successfully indexed pages
     */
    public int indexAllPages() {
        int successCount = 0;
        try {
            Path cacheDir = Path.of("cache_pages");
            
            // Get all .html files in cache directory
            List<Integer> pageIds = Files.list(cacheDir)
                .filter(path -> path.toString().endsWith(".html"))
                .map(path -> {
                    String filename = path.getFileName().toString();
                    return Integer.parseInt(filename.replace(".html", ""));
                })
                .sorted()
                .collect(Collectors.toList());

            System.out.println("Found " + pageIds.size() + " cached pages to index");

            for (int pageId : pageIds) {
                if (indexPage(pageId)) {
                    successCount++;
                }
            }

            System.out.println("Successfully indexed " + successCount + "/" + pageIds.size() + " pages");
            return successCount;

        } catch (IOException e) {
            System.err.println("Error listing cache directory: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Load HTML content from cache file.
     * 
     * @param pageId Page ID (file is cache_pages/{pageId}.html)
     * @return HTML content, or null if not found
     */
    private String loadHtmlFromCache(int pageId) throws IOException {
        Path cacheFile = Path.of("cache_pages").resolve(pageId + ".html");
        if (!Files.exists(cacheFile)) {
            return null;
        }
        return Files.readString(cacheFile, StandardCharsets.UTF_8);
    }

    /**
     * Store words and keywords in the database.
     * 
     * For each word:
     * 1. Insert word into "words" table (if not exists)
     * 2. Insert word-page relationship with frequency into "keywords" table
     * 
     * @param pageId Page ID
     * @param wordFreq Map of word → frequency
     */
    private void storeWordsAndKeywords(int pageId, Map<String, Integer> wordFreq) {
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            String word = entry.getKey();
            int frequency = entry.getValue();

            // Step 1: Get or create word ID
            Integer wordId = getOrCreateWordId(word);
            if (wordId == null) {
                System.err.println("Failed to get word ID for: " + word);
                continue;
            }

            // Step 2: Insert into keywords table
            insertKeyword(pageId, wordId, frequency);
        }
    }

    /**
     * Get word ID, or insert word if it doesn't exist.
     * 
     * SQL: INSERT OR IGNORE → tries to insert, ignores if already exists (UNIQUE constraint)
     * Then: SELECT to get the ID
     * 
     * @param word The word to look up or create
     * @return Word ID, or null if error
     */
    private Integer getOrCreateWordId(String word) {
        try {
            // Try to insert (will be ignored if already exists due to UNIQUE constraint)
            db.update("INSERT OR IGNORE INTO words(word) VALUES (?)", word);

            // Retrieve the word ID
            Integer wordId = db.queryForObject(
                "SELECT id FROM words WHERE word = ?",
                Integer.class,
                word
            );
            return wordId;

        } catch (Exception e) {
            System.err.println("Error getting/creating word ID for '" + word + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Insert or update keyword frequency in the database.
     * 
     * @param pageId Page ID
     * @param wordId Word ID
     * @param frequency Word frequency/count on this page
     */
    private void insertKeyword(int pageId, int wordId, int frequency) {
        try {
            // Use INSERT OR REPLACE to handle updates
            db.update(
                "INSERT OR REPLACE INTO keywords(page_id, word_id, count) VALUES (?, ?, ?)",
                pageId,
                wordId,
                frequency
            );
        } catch (Exception e) {
            System.err.println("Error inserting keyword: page=" + pageId + 
                             ", word=" + wordId + ", freq=" + frequency + 
                             ": " + e.getMessage());
        }
    }

    /**
     * Search for keywords matching a pattern (case-insensitive wildcard search).
     * 
     * Query: SELECT word FROM words WHERE word LIKE %query%
     * 
     * @param query Search query (e.g., "java" finds "java", "javascript", "coffee")
     * @return List of matching keywords
     */
    public List<String> searchKeywords(String query) {
        if (query == null || query.isEmpty()) {
            return new ArrayList<>();
        }
        
        String searchPattern = "%" + query.toLowerCase() + "%";
        return db.query(
            "SELECT word FROM words WHERE LOWER(word) LIKE ? ORDER BY word ASC",
            new Object[]{searchPattern},
            (rs, rowNum) -> rs.getString("word")
        );
    }

    /**
     * Get statistics about what's been indexed.
     * 
     * @return String with index statistics
     */
    public String getIndexStats() {
        Integer totalWords = db.queryForObject("SELECT COUNT(*) FROM words", Integer.class);
        Integer totalKeywords = db.queryForObject("SELECT COUNT(*) FROM keywords", Integer.class);
        Integer totalPages = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);

        return String.format(
            "Index Stats: %d pages, %d unique words, %d keyword entries",
            totalPages == null ? 0 : totalPages,
            totalWords == null ? 0 : totalWords,
            totalKeywords == null ? 0 : totalKeywords
        );
    }
}
