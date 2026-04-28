package com.hkust.goooogle.services;

import IRUtilities.Porter;
import com.hkust.goooogle.models.IndexerStats;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public final IndexerStats stats;

    public IndexerStats getStats() {
        return stats;
    }

    public IndexerService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
        this.stats = new IndexerStats(this::doUpdateStats);
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

        storeWordsAndKeywords(pageId, bodyWords, titleWords, body.length(), title.length());

        System.out.println("Indexed page " + pageId + " with " + bodyWords.size() + " words in body and " + titleWords.size() + " words in title");
        return true;
    }

    private static final String newLineHtmlKeywords = "br, p, div, h1, h2, h3, section, article, header, footer, nav, aside";
    private String parseBody(Document doc) {
        doc.select(newLineHtmlKeywords).append("\n");
        return doc.body().text();
    }

    private static final Pattern wordSplitPattern = Pattern.compile("[\\s/\\-]+");
    public Map<String, Long> computeWordDistribution(String content) {
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

    private static final String INSERT_WORDS_SQL = "INSERT OR IGNORE INTO words(word) VALUES (?)";
    private static final String INSERT_KEYWORDS_SQL = "INSERT OR REPLACE INTO keywords(page_id, word_id, body_count, title_count, weighted_count) " +
                                                      "SELECT ?, id, ?, ?, ? FROM words WHERE word = ?";
    private void storeWordsAndKeywords(int pageId, Map<String, Long> bodyWords, Map<String, Long> titleWords, int bodyLength, int titleLength) {
        if (bodyWords.isEmpty() && titleWords.isEmpty()) return;
        Set<String> allWords = new HashSet<>();
        allWords.addAll(bodyWords.keySet());
        allWords.addAll(titleWords.keySet());

        if (db.getDataSource() == null) return;

        try (java.sql.Connection conn = db.getDataSource().getConnection();
             java.sql.PreparedStatement psWords = conn.prepareStatement(INSERT_WORDS_SQL);
             java.sql.PreparedStatement psKeywords = conn.prepareStatement(INSERT_KEYWORDS_SQL)) {

            // 關閉自動提交，能極大提升批量插入的性能
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            int bodyWordsLength = bodyWords.size();
            int titleWordsLength = titleWords.size();

            // 批量裝載新單詞
            for (String word : allWords) {
                psWords.setString(1, word);
                psWords.addBatch();
            }
            psWords.executeBatch();

            // 批量裝載關鍵詞
            float beta = 100.0f;
            float wt = (beta * titleWordsLength) / (beta * titleWordsLength + bodyWordsLength);
            float invWt = 1.0f - wt;
            
            for (String word : allWords) {
                long bodyCount = bodyWords.getOrDefault(word, 0L);
                long titleCount = titleWords.getOrDefault(word, 0L);
                float weightedCount = wt * titleCount + invWt * bodyCount;
                
                psKeywords.setInt(1, pageId);
                psKeywords.setLong(2, bodyCount);
                psKeywords.setLong(3, titleCount);
                psKeywords.setFloat(4, weightedCount);
                psKeywords.setString(5, word);
                psKeywords.addBatch();
            }
            psKeywords.executeBatch();

            // 手動提交事務
            conn.commit();
            conn.setAutoCommit(originalAutoCommit);

        } catch (Exception e) {
            System.err.println("Error storing words and keywords: " + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void resolvePendingLinksFromAllPages() {
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

    // 不要直接呼叫，應該呼叫 stats.update() 來觸發更新
    private void doUpdateStats(IndexerStats stats) {
        try {
            Integer indexedPages = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
            Integer pendingPages = db.queryForObject("SELECT COUNT(*) FROM pending_links", Integer.class);
            Integer indexedWords = db.queryForObject("SELECT COUNT(*) FROM words", Integer.class);
            stats.setStats(indexedPages, pendingPages, indexedWords);
        } catch (Exception e) {
            System.err.println("Failed to access database for stats update: " + e.getMessage());
            return;
        }
    }

}
