package com.hkust.goooogle.services;

import IRUtilities.Porter;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public boolean indexPage(int pageId, String pageUrl, Document doc) {
        if (doc == null) return false;

        String title = doc.title();
        Map<String, Long> titleWords = computeWordDistribution(title);

        String body = parseBody(doc);
        Map<String, Long> bodyWords = computeWordDistribution(body);

        storeWordsAndKeywords(pageId, bodyWords, titleWords);

        System.out.println("Indexed page " + pageId + " with " + bodyWords.size() + " words in body and " + titleWords.size() + " words in title");
        return true;
    }

    private static final String newLineHtmlKeywords = "br, p, div, h1, h2, h3, section, article, header, footer, nav, aside";
    private String parseBody(Document doc) {
        doc.select(newLineHtmlKeywords).append("\n");
        return doc.body().text();
    }

    private static final Pattern wordSplitPattern = Pattern.compile("[\\s/\\-]+");
    private Map<String, Long> computeWordDistribution(String content) {
        if (content.isBlank()) return Collections.emptyMap();

        return wordSplitPattern.splitAsStream(content.toLowerCase().trim())
            .map(w -> removeTrailingPunctuation(w))
            .filter(w -> !w.isEmpty())
            .flatMap(w -> expandContractions(w))
            .flatMap(w -> expandSymbols(w))
            .map(w -> w.endsWith("'s") ? w.substring(0, w.length() - 2) : w)
            .filter(w -> !stopWords.contains(w))
            .map(w -> stemmer.stripAffixes(w))
            .filter(w -> !w.isEmpty())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    public static String removeTrailingPunctuation(String input) {
        if (input.isEmpty()) return input;
        int end = input.length();
        while (end > 0 && !Character.isLetterOrDigit(input.charAt(end - 1))) {
            end--;
        }
        return input.substring(0, end);
    }

    private Stream<String> expandContractions(String word) {
        String expanded = contractions.get(word);
        if (expanded == null) return Stream.of(word);
        return Arrays.stream(expanded.split(" "));
    }

    private Stream<String> expandSymbols(String word) {
        for (Map.Entry<String, String> entry : symbols.entrySet()) {
            String suffix = entry.getKey();
            if (word.endsWith(suffix)) {
                String firstPart = word.substring(0, word.length() - suffix.length());
                return Arrays.stream(new String[]{firstPart, entry.getValue()});
            }
        }
        return Stream.of(word);
    }

    private void storeWordsAndKeywords(int pageId, Map<String, Long> bodyWords, Map<String, Long> titleWords) {
        if (bodyWords.isEmpty() && titleWords.isEmpty()) return;
        Set<String> allWords = new HashSet<>();
        allWords.addAll(bodyWords.keySet());
        allWords.addAll(titleWords.keySet());

        batchInsertWords(new ArrayList<>(allWords));
        Map<String, Integer> wordIds = batchGetWordIds(new ArrayList<>(allWords));

        List<Object[]> keywordBatch = new ArrayList<>();
        for (String word : allWords) {
            Integer wordId = wordIds.get(word);
            if (wordId != null) {
                long bodyCount = bodyWords.getOrDefault(word, 0L);
                long titleCount = titleWords.getOrDefault(word, 0L);
                keywordBatch.add(new Object[]{pageId, wordId, bodyCount, titleCount});
            }
        }

        if (!keywordBatch.isEmpty()) {
            batchInsertKeywords(keywordBatch);
        }
    }

    private void batchInsertWords(List<String> words) {
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

    public void resolvePendingLinksFromAllPages() {
        try {
            // 找到所有 pending_links 中的 outbound_link 已經存在於 pages 表中的，並且將它們轉換為 links 記錄
            final String insertLinksSQL = """
                INSERT OR IGNORE INTO links(parent_page_id, child_page_id)
                SELECT DISTINCT pl.page_id, p.id
                FROM pending_links pl
                JOIN pages p ON pl.outbound_link = p.url
                WHERE pl.page_id != p.id
                """;
            
            db.update(insertLinksSQL);

            // 刪除已經解決的 pending_links 記錄
            final String deleteResolvedSQL = """
                DELETE FROM pending_links
                WHERE outbound_link IN (SELECT url FROM pages)
                """;
            
            db.update(deleteResolvedSQL);
            
            Integer remainingPending = db.queryForObject("SELECT COUNT(*) FROM pending_links", Integer.class);
            System.out.println("Remaining pending entries: " + (remainingPending == null ? 0 : remainingPending) + " (URLs not yet crawled)");
            
        } catch (Exception e) {
            System.err.println("Error resolving pending links from all pages: " + e.getMessage());
            e.printStackTrace();
        }
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
