package com.hkust.goooogle.services;

import IRUtilities.Porter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IndexerService {

    private final JdbcTemplate db;
    private final Set<String> stopWords;
    private final Map<String, String> contractions;
    private final Map<String, String> symbols;
    private final Porter stemmer;

    public IndexerService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
        this.stopWords = loadSetFromResource("stopwords.txt");
        this.contractions = loadMapFromResource("contractions.txt");
        this.symbols = loadMapFromResource("symbols.txt");
        this.stemmer = new Porter();
    }

    private Set<String> loadSetFromResource(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource(filename);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Set.copyOf(
                Arrays.stream(content.split("\\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toSet())
            );
        } catch (IOException e) {
            System.err.println("Failed to load " + filename + ": " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private Map<String, String> loadMapFromResource(String filename) {
        Map<String, String> map = new HashMap<>();
        try {
            ClassPathResource resource = new ClassPathResource(filename);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Arrays.stream(content.split("\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && line.contains("->"))
                .forEach(line -> {
                    String[] parts = line.split("->");
                    if (parts.length == 2) {
                        map.put(parts[0].trim(), parts[1].trim());
                    }
                });
        } catch (IOException e) {
            System.err.println("Failed to load " + filename + ": " + e.getMessage());
        }
        return map;
    }

    public Map<String, Integer> getWordsFreq(Document doc) {
        doc.select("br").before(" ").remove();
        
        String text = doc.body().text();
        
        String lowerText = text.toLowerCase();
        
        String[] rawWords = lowerText.split("[\\s/\\-]+");
        
        Map<String, Integer> wordFreq = new HashMap<>();
        
        for (String rawWord : rawWords) {
            if (rawWord.isEmpty()) {
                continue;
            }
            
            String expanded = expandContractions(rawWord);
            
            expanded = expandSymbols(expanded);
            
            expanded = expanded.replaceAll("'s$", "");
            
            for (String word : expanded.split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                
                if (word.length() < 2) {
                    continue;
                }
                
                if (isStopWord(word)) {
                    continue;
                }
                
                String stemmed = stemmer.stripAffixes(word);
                
                wordFreq.put(stemmed, wordFreq.getOrDefault(stemmed, 0) + 1);
            }
        }
        
        return wordFreq;
    }

    private String expandContractions(String word) {
        return contractions.getOrDefault(word, word);
    }

    private String expandSymbols(String word) {
        String result = word;
        for (Map.Entry<String, String> entry : symbols.entrySet()) {
            result = result.replace(entry.getKey(), " " + entry.getValue() + " ");
        }
        return result;
    }

    private boolean isStopWord(String word) {
        return stopWords.contains(word);
    }

    public boolean indexPage(int pageId, String pageUrl) {
        try {
            String html = loadHtmlFromCache(pageId);
            if (html == null) {
                System.err.println("Cache file not found for page ID: " + pageId);
                return false;
            }

            Document doc = Jsoup.parse(html, pageUrl);

            return indexPage(pageId, pageUrl, doc);

        } catch (Exception e) {
            System.err.println("Error indexing page " + pageId + ": " + e.getMessage());
            return false;
        }
    }

    public boolean indexPage(int pageId, String pageUrl, Document doc) {
        if (doc == null) {
            System.err.println("Document is null for page ID: " + pageId);
            return false;
        }

        String title = doc.title();
        Map<String, Integer> titleWords = title != null && !title.isEmpty() ? 
            extractWordsFromText(title) : new HashMap<>();

        Map<String, Integer> bodyWords = getWordsFreq(doc);

        storeWordsAndKeywords(pageId, bodyWords, titleWords);

        extractAndStorePendingLinks(pageId, doc);

        resolvePendingLinks(pageId, pageUrl);

        System.out.println("Indexed page " + pageId + " with " + bodyWords.size() + " unique words");
        return true;
    }

    private String loadHtmlFromCache(int pageId) throws IOException {
        Path cacheFile = Path.of("cache_pages").resolve(pageId + ".html");
        if (!Files.exists(cacheFile)) {
            return null;
        }
        return Files.readString(cacheFile, StandardCharsets.UTF_8);
    }

    private Map<String, Integer> extractWordsFromText(String text) {
        String lowerText = text.toLowerCase();
        String[] rawWords = lowerText.split("[\\s/\\-]+");
        Map<String, Integer> wordFreq = new HashMap<>();
        
        for (String rawWord : rawWords) {
            if (rawWord.isEmpty()) continue;
            
            String expanded = expandContractions(rawWord);
            expanded = expandSymbols(expanded);
            expanded = expanded.replaceAll("'s$", "");
            
            for (String word : expanded.split("\\s+")) {
                if (word.isEmpty() || word.length() < 2 || isStopWord(word)) continue;
                String stemmed = stemmer.stripAffixes(word);
                wordFreq.put(stemmed, wordFreq.getOrDefault(stemmed, 0) + 1);
            }
        }
        return wordFreq;
    }

    private void storeWordsAndKeywords(int pageId, Map<String, Integer> bodyWords, Map<String, Integer> titleWords) {
        Set<String> allWords = new HashSet<>();
        allWords.addAll(bodyWords.keySet());
        allWords.addAll(titleWords.keySet());
        
        if (allWords.isEmpty()) {
            return;
        }

        batchInsertWords(new ArrayList<>(allWords));
        Map<String, Integer> wordIds = batchGetWordIds(new ArrayList<>(allWords));

        List<Object[]> keywordBatch = new ArrayList<>();
        for (String word : allWords) {
            Integer wordId = wordIds.get(word);
            if (wordId != null) {
                int bodyCount = bodyWords.getOrDefault(word, 0);
                int titleCount = titleWords.getOrDefault(word, 0);
                keywordBatch.add(new Object[]{pageId, wordId, bodyCount, titleCount});
            }
        }

        if (!keywordBatch.isEmpty()) {
            batchInsertKeywords(keywordBatch);
        }
    }

    private void batchInsertWords(List<String> words) {
        if (words.isEmpty()) return;

        try {
            List<Object[]> batch = words.stream()
                .map(word -> new Object[]{word})
                .collect(Collectors.toList());

            db.batchUpdate("INSERT OR IGNORE INTO words(word) VALUES (?)", batch);
        } catch (Exception e) {
            System.err.println("Error batch inserting words: " + e.getMessage());
        }
    }

    private Map<String, Integer> batchGetWordIds(List<String> words) {
        if (words.isEmpty()) return new HashMap<>();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        String sql = "SELECT id, word FROM words WHERE word IN (" + placeholders + ")";

        try {
            List<Map<String, Object>> results = db.queryForList(sql, words.toArray());
            Map<String, Integer> wordIds = new HashMap<>();
            for (Map<String, Object> row : results) {
                wordIds.put((String) row.get("word"), ((Number) row.get("id")).intValue());
            }
            return wordIds;
        } catch (Exception e) {
            System.err.println("Error fetching word IDs: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void batchInsertKeywords(List<Object[]> keywordBatch) {
        if (keywordBatch.isEmpty()) return;

        try {
            db.batchUpdate("INSERT OR REPLACE INTO keywords(page_id, word_id, body_count, title_count) VALUES (?, ?, ?, ?)", keywordBatch);
        } catch (Exception e) {
            System.err.println("Error batch inserting keywords: " + e.getMessage());
        }
    }

    private void extractAndStorePendingLinks(int pageId, Document doc) {
        try {
            List<String> hyperlinks = doc.select("a[href]")
                .stream()
                .map(element -> element.absUrl("href"))
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

            System.out.println("Found " + hyperlinks.size() + " hyperlinks in page " + pageId);
            if (hyperlinks.isEmpty()) {
                System.out.println("No hyperlinks to store for page " + pageId);
                return;
            }

            List<Object[]> pendingBatch = hyperlinks.stream()
                .map(url -> new Object[]{pageId, url})
                .collect(Collectors.toList());

            db.batchUpdate("INSERT OR IGNORE INTO pending(page_id, child_page_link) VALUES (?, ?)", pendingBatch);
            System.out.println("Successfully stored " + pendingBatch.size() + " pending links for page " + pageId);
            
            Integer pendingCount = db.queryForObject("SELECT COUNT(*) FROM pending WHERE page_id = ?", Integer.class, pageId);
            System.out.println("Pending table now has " + (pendingCount == null ? 0 : pendingCount) + " entries for page " + pageId);
        } catch (Exception e) {
            System.err.println("Error storing pending links for page " + pageId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void resolvePendingLinks(int pageId, String pageUrl) {
        try {
            System.out.println("Attempting to resolve pending links for page " + pageId + " with URL: " + pageUrl);
            
            Integer pendingCount = db.queryForObject(
                "SELECT COUNT(*) FROM pending WHERE child_page_link = ?",
                Integer.class,
                pageUrl
            );
            System.out.println("Found " + (pendingCount == null ? 0 : pendingCount) + " pending entries for URL: " + pageUrl);
            
            if (pendingCount == null || pendingCount == 0) {
                System.out.println("No pending links to resolve for page " + pageId);
                return;
            }
            
            int rowsInserted = db.update(
                "INSERT OR IGNORE INTO links(parent_page_id, child_page_id) " +
                "SELECT page_id, ? FROM pending WHERE child_page_link = ? AND page_id != ?",
                pageId,
                pageUrl,
                pageId
            );
            System.out.println("Resolved and inserted " + rowsInserted + " parent-child links for page " + pageId);
            
            int rowsDeleted = db.update(
                "DELETE FROM pending WHERE child_page_link = ?",
                pageUrl
            );
            System.out.println("Cleaned up " + rowsDeleted + " resolved pending entries for URL: " + pageUrl);
            
            Integer linksCount = db.queryForObject("SELECT COUNT(*) FROM links WHERE child_page_id = ?", Integer.class, pageId);
            System.out.println("Links table now has " + (linksCount == null ? 0 : linksCount) + " entries with page " + pageId + " as child");
        } catch (Exception e) {
            System.err.println("Error resolving pending links for page " + pageId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void cleanupPendingLinks() {
        try {
            Integer pendingCount = db.queryForObject("SELECT COUNT(*) FROM pending", Integer.class);
            if (pendingCount == null || pendingCount == 0) {
                System.out.println("No pending links to clean up");
                return;
            }

            int rowsDeleted = db.update(
                "DELETE FROM pending WHERE child_page_link IN (SELECT url FROM pages)"
            );
            System.out.println("Cleaned up " + rowsDeleted + " redundant pending entries (child pages already indexed)");

            Integer remainingCount = db.queryForObject("SELECT COUNT(*) FROM pending", Integer.class);
            System.out.println("Remaining pending entries: " + (remainingCount == null ? 0 : remainingCount) + " (not yet crawled)");
        } catch (Exception e) {
            System.err.println("Error cleaning up pending links: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
