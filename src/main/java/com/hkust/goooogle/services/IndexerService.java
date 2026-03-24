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

    public boolean indexPage(int pageId) {
        try {
            String html = loadHtmlFromCache(pageId);
            if (html == null) {
                System.err.println("Cache file not found for page ID: " + pageId);
                return false;
            }

            Document doc = Jsoup.parse(html);

            Map<String, Integer> wordFreq = getWordsFreq(doc);

            storeWordsAndKeywords(pageId, wordFreq);

            System.out.println("Indexed page " + pageId + " with " + wordFreq.size() + " unique words");
            return true;

        } catch (Exception e) {
            System.err.println("Error indexing page " + pageId + ": " + e.getMessage());
            return false;
        }
    }

    public int indexAllPages() {
        int successCount = 0;
        try {
            Path cacheDir = Path.of("cache_pages");
            
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

    private String loadHtmlFromCache(int pageId) throws IOException {
        Path cacheFile = Path.of("cache_pages").resolve(pageId + ".html");
        if (!Files.exists(cacheFile)) {
            return null;
        }
        return Files.readString(cacheFile, StandardCharsets.UTF_8);
    }

    private void storeWordsAndKeywords(int pageId, Map<String, Integer> wordFreq) {
        for (Map.Entry<String, Integer> entry : wordFreq.entrySet()) {
            String word = entry.getKey();
            int frequency = entry.getValue();

            Integer wordId = getOrCreateWordId(word);
            if (wordId == null) {
                System.err.println("Failed to get word ID for: " + word);
                continue;
            }

            insertKeyword(pageId, wordId, frequency);
        }
    }

    private Integer getOrCreateWordId(String word) {
        try {
            db.update("INSERT OR IGNORE INTO words(word) VALUES (?)", word);

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

    private void insertKeyword(int pageId, int wordId, int frequency) {
        try {
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
