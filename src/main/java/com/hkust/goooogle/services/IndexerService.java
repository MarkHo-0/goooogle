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

        storeHyperlinksAsPending(pageId, doc);

        System.out.println("Indexed page " + pageId + " with " + bodyWords.size() + " unique words");
        return true;
    }

    private static final String newLineHtmlKeywords = "br, p, div, h1, h2, h3, section, article, header, footer, nav, aside";
    private String parseBody(Document doc) {
        doc.select(newLineHtmlKeywords).append("\n");
        return doc.body().text();
    }

    private static final Pattern wordSplitPattern = Pattern.compile("[\\s/\\-]+");
    private Map<String, Long> computeWordDistribution(String content) {
        if (content.trim().isEmpty()) {
            return Collections.emptyMap();
        }

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

    private void storeHyperlinksAsPending(int pageId, Document doc) {
        try {
            List<String> hyperlinks = doc.select("a[href]")
                .stream()
                .map(element -> element.absUrl("href"))
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

            if (hyperlinks.isEmpty()) {
                System.out.println("No hyperlinks to store for page " + pageId);
                return;
            }

            List<Object[]> pendingBatch = new ArrayList<>();
            for (String url : hyperlinks) {
                pendingBatch.add(new Object[]{pageId, url});
            }

            db.batchUpdate("INSERT OR IGNORE INTO pending(page_id, child_page_link) VALUES (?, ?)", pendingBatch);
            System.out.println("Stored " + pendingBatch.size() + " hyperlinks as pending for page " + pageId);

        } catch (Exception e) {
            System.err.println("Error storing hyperlinks as pending for page " + pageId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void resolvePendingLinksFromAllPages() {
        try {
            Integer pendingCount = db.queryForObject("SELECT COUNT(*) FROM pending", Integer.class);
            System.out.println("Starting to resolve " + (pendingCount == null ? 0 : pendingCount) + " pending links");
            
            if (pendingCount == null || pendingCount == 0) {
                System.out.println("No pending links to resolve");
                return;
            }

            List<Map<String, Object>> pendingEntries = db.queryForList(
                "SELECT rowid, page_id, child_page_link FROM pending ORDER BY rowid ASC"
            );

            System.out.println("Processing " + pendingEntries.size() + " pending entries (BFS order preserved by rowid)");

            int resolvedCount = 0;
            int deletedCount = 0;
            
            Set<String> existingLinkPairs = getExistingLinkPairs();

            for (Map<String, Object> pendingEntry : pendingEntries) {
                int parentPageId = ((Number) pendingEntry.get("page_id")).intValue();
                String childPageUrl = (String) pendingEntry.get("child_page_link");
                long rowid = ((Number) pendingEntry.get("rowid")).longValue();
                
                Integer childPageId = db.queryForObject(
                    "SELECT id FROM pages WHERE url = ?",
                    Integer.class,
                    childPageUrl
                );

                if (childPageId != null && childPageId != parentPageId) {
                    String forwardKey = parentPageId + "-" + childPageId;
                    String reverseKey = childPageId + "-" + parentPageId;
                    
                    if (!existingLinkPairs.contains(forwardKey) && !existingLinkPairs.contains(reverseKey)) {
                        int rows = db.update(
                            "INSERT OR IGNORE INTO links(parent_page_id, child_page_id) VALUES (?, ?)",
                            parentPageId,
                            childPageId
                        );
                        if (rows > 0) {
                            existingLinkPairs.add(forwardKey);
                            resolvedCount++;
                            System.out.println("Created link: " + parentPageId + " -> " + childPageId + " (from URL: " + childPageUrl + ")");
                        }
                    } else if (existingLinkPairs.contains(reverseKey)) {
                        System.out.println("Skipped link (" + parentPageId + "," + childPageId + ") - reverse link exists");
                    }
                }

                int deleted = db.update("DELETE FROM pending WHERE rowid = ?", rowid);
                if (deleted > 0) {
                    deletedCount++;
                }
            }

            System.out.println("Resolved pending links: created " + resolvedCount + " links, deleted " + deletedCount + " pending entries");
            
            Integer remainingPending = db.queryForObject("SELECT COUNT(*) FROM pending", Integer.class);
            System.out.println("Remaining pending entries: " + (remainingPending == null ? 0 : remainingPending) + " (URLs not yet in pages table)");
            
        } catch (Exception e) {
            System.err.println("Error resolving pending links from all pages: " + e.getMessage());
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

    private Set<String> getExistingLinkPairs() {
        try {
            Set<String> linkPairs = new HashSet<>();
            List<Map<String, Object>> links = db.queryForList(
                "SELECT parent_page_id, child_page_id FROM links"
            );
            for (Map<String, Object> link : links) {
                int parent = ((Number) link.get("parent_page_id")).intValue();
                int child = ((Number) link.get("child_page_id")).intValue();
                linkPairs.add(parent + "-" + child);
            }
            return linkPairs;
        } catch (Exception e) {
            System.err.println("Error fetching existing link pairs: " + e.getMessage());
            return Collections.emptySet();
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
