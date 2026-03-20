package com.hkust.goooogle.services;

import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.List;
import java.util.Locale;

@Service
public class SpiderService {
    enum SpiderState {
        IDLE,
        CRAWLING,
        WAITING_BETWEEN_BATCHES,
    }

    private final JdbcTemplate db;
    private final Queue<String> pendingCrawlUrls = new LinkedList<>();
    private int totalCrawledThisRun = 0;

    private SpiderState state = SpiderState.IDLE;
    private int maxPagesLimit = 0;
    private int batchSizeLimit = 0;
    private int betweenBatchesDelayMillis = 0;

    private record ExistingPageInfo(int id, String lastModifyTime) {}

    public SpiderService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
    }

    public boolean startSpider(String url, int maxPages, int batchSize, int betweenBatchesDelayMillis) {
        if (state != SpiderState.IDLE) {
            return false;
        }

        if (maxPages <= 0 || batchSize <= 0 || betweenBatchesDelayMillis < 0) {
            return false;
        }

        String normalizedStartUrl = normalizeUrl(url);
        if (normalizedStartUrl == null) {
            return false;
        }

        pendingCrawlUrls.clear();
        totalCrawledThisRun = 0;
        this.maxPagesLimit = maxPages;
        this.batchSizeLimit = batchSize;
        this.betweenBatchesDelayMillis = betweenBatchesDelayMillis;
        pendingCrawlUrls.offer(normalizedStartUrl);

        Thread worker = new Thread(this::executeCrawlingBatches, "spider-main-thread");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void executeCrawlingBatches() {
        while (!shouldStopSpider()) {
            int currentBatchLimit = betweenBatchesDelayMillis == 0 ? maxPagesLimit : batchSizeLimit;
            int crawledInBatch = 0;

            while (crawledInBatch < currentBatchLimit) {
                String nextUrl = pendingCrawlUrls.poll();
                if (nextUrl == null) break;

                if (crawlPage(nextUrl)) {
                    crawledInBatch++;
                }
            }

            sleepBetweenBatches();
        }

        resetSpider();
    }

    private boolean crawlPage(String url) {
        setState(SpiderState.CRAWLING);

        try {
            ExistingPageInfo existingPage = getExistingPageInfo(url);
            Connection connection = Jsoup.connect(url).followRedirects(true).ignoreHttpErrors(true);

            // 如果数据库中已有记录且包含上次修改时间，则在请求头中添加 If-Modified-Since，以利用服务器的缓存机制减少不必要的数据传输
            if (existingPage != null && existingPage.lastModifyTime() != null && !existingPage.lastModifyTime().isBlank()) {
                connection.header("If-Modified-Since", existingPage.lastModifyTime());
            }

            // 下載頁面
            Connection.Response response = connection.execute();

            if (response.statusCode() >= 400) {
                throw new IOException(response.statusCode() + " Error Code");
            }

            if (response.statusCode() == 304) {
                throw new IOException("Not Modified");
            }

            // 解析页面内容并更新数据库记录
            Document document = response.parse();
            int pageId = upsertPage(url, document, existingPage);
            savePageToLocalCache(pageId, document.outerHtml());
            addChildLinksToPendingQueue(document);
            System.out.println("Crawled Successfully: " + url);

            totalCrawledThisRun++;
            return true;
        } catch (Exception ex) {
            System.err.println("Failed to crawl: " + url + ": " + ex.getMessage());
            return false;
        }
    }

    private int upsertPage(String url, Document document, ExistingPageInfo existingPage) {
        String lastModifyTime = extractLastModifyTime(document);
        int contentSize = document.outerHtml().getBytes(StandardCharsets.UTF_8).length;
        String title = document.title();

        if (existingPage != null) {
            db.update(
                "UPDATE pages SET title = ?, last_modify_time = ?, content_size = ? WHERE id = ?",
                title,
                lastModifyTime,
                contentSize,
                existingPage.id()
            );
            return existingPage.id();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = db.update(connection -> {
            var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO pages(url, title, last_modify_time, content_size) VALUES (?, ?, ?, ?)",
                new String[]{"id"}
            );
            statement.setString(1, url);
            statement.setString(2, title);
            statement.setString(3, lastModifyTime);
            statement.setInt(4, contentSize);
            return statement;
        }, keyHolder);

        if (rows == 0) throw new RuntimeException("Failed to insert page into database");

        return keyHolder.getKey().intValue();
    }

    private ExistingPageInfo getExistingPageInfo(String url) {
        return db.query(
            "SELECT id, last_modify_time FROM pages WHERE url = ? LIMIT 1",
            rs -> {
                if (!rs.next()) return null;

                int id = rs.getInt("id");
                String lastModifyTime = rs.getString("last_modify_time");
                return new ExistingPageInfo(id, lastModifyTime);
            },
            url
        );
    }

    private void savePageToLocalCache(int pageId, String html) throws IOException {
        Path cacheDir = Path.of("cache_pages");
        Files.createDirectories(cacheDir);
        Path cacheFilePath = cacheDir.resolve(pageId + ".html");
        Files.writeString(cacheFilePath, html, StandardCharsets.UTF_8);
    }

    private void addChildLinksToPendingQueue(Document document) {
        List<String> links = document.select("a[href]")
            .stream()
            .map(element -> normalizeUrl(element.absUrl("href")))
            .filter(url -> url != null && !url.isBlank())
            .toList();

        for (String link : links) {
            if (!isPageAlreadyCrawled(link) && !pendingCrawlUrls.contains(link)) {
                pendingCrawlUrls.offer(link);
            }
        }
    }

    private boolean isPageAlreadyCrawled(String url) {
        List<Integer> rows = db.queryForList(
            "SELECT 1 FROM pages WHERE url = ? LIMIT 1",
            Integer.class,
            url
        );
        return !rows.isEmpty();
    }

    private String extractLastModifyTime(Document document) {
        String lastModifiedHeader = document.connection().response().header("Last-Modified");
        DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

        if (lastModifiedHeader == null || lastModifiedHeader.isBlank()) {
            return ZonedDateTime.now(ZoneOffset.UTC).format(formatter);
        }

        try {
            ZonedDateTime parsed = ZonedDateTime.parse(lastModifiedHeader, formatter);
            return parsed.format(formatter);
        } catch (DateTimeParseException ex) {
            return ZonedDateTime.now(ZoneOffset.UTC).format(formatter);
        }
    }

    private String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        String trimmed = rawUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }

        int fragmentIndex = trimmed.indexOf('#');
        return fragmentIndex >= 0 ? trimmed.substring(0, fragmentIndex) : trimmed;
    }

    private boolean shouldStopSpider() {
        return totalCrawledThisRun >= maxPagesLimit || pendingCrawlUrls.isEmpty();
    }

    private void sleepBetweenBatches() {
        if (betweenBatchesDelayMillis <= 0 || shouldStopSpider()) {
            return;
        }

        setState(SpiderState.WAITING_BETWEEN_BATCHES);

        try {
            Thread.sleep(betweenBatchesDelayMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public void setState(SpiderState newState) {
        this.state = newState;
    }

    private void resetSpider() {
        pendingCrawlUrls.clear();
        totalCrawledThisRun = 0;
        maxPagesLimit = 30;
        batchSizeLimit = 10;
        betweenBatchesDelayMillis = 1000;
        setState(SpiderState.IDLE);
    }

    public int getTotalIndexedPages() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
        return count == null ? 0 : count;
    }

    public boolean isRunning() {
        return state == SpiderState.CRAWLING || state == SpiderState.WAITING_BETWEEN_BATCHES;
    }
}