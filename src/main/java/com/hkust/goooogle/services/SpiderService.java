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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

@Service
public class SpiderService {
    private final JdbcTemplate db;
    private final IndexerService indexerService;
    private final Queue<String> pendingCrawlUrls = new LinkedList<>();
    private final Set<String> urlCrawlHistory = new HashSet<>();
    private int remainingCrawlQuota = 0;
    private volatile boolean cacheHtmlEnabled = false;

    private volatile boolean running = false;

    private record ExistingPageInfo(int id, String lastModifyTime) {}
    private record CrawledPage(int pageId, String url, Document document) {}

    public SpiderService(JdbcTemplate jdbcTemplate, IndexerService indexerService) {
        this.db = jdbcTemplate;
        this.indexerService = indexerService;
    }

    public synchronized boolean startSpider(String url, int maxPages, boolean cacheHtml) {
        if (running) {
            return false;
        }

        if (maxPages <= 0) {
            return false;
        }

        String normalizedStartUrl = normalizeUrl(url);
        if (normalizedStartUrl == null) {
            return false;
        }

        remainingCrawlQuota = maxPages;
        cacheHtmlEnabled = cacheHtml;
        pendingCrawlUrls.offer(normalizedStartUrl);
        running = true;

        Thread worker = new Thread(this::executeCrawlingAndIndexing, "spider-main-thread");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void executeCrawlingAndIndexing() {
        while (remainingCrawlQuota > 0) {
            String nextUrl = pendingCrawlUrls.poll();
            if (nextUrl == null) break;

            CrawledPage crawledPage = crawlPage(nextUrl);
            if (crawledPage == null) continue;

            addChildLinksToPendingQueue(crawledPage.document(), crawledPage.pageId());

            indexerService.indexPage(crawledPage.pageId(), crawledPage.url(), crawledPage.document());
            remainingCrawlQuota--;
        }

        indexerService.resolvePendingLinksFromAllPages();

        resetSpider();
    }

    private CrawledPage crawlPage(String url) {
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
                System.err.println("Failed to crawl (HTTP " + response.statusCode() + "): " + url);
                return null;
            }

            if (response.statusCode() == 304) {
                System.out.println("Skipped (Not Modified): " + url);
                return null;
            }

            // 解析页面内容并更新数据库记录
            Document document = response.parse();
            int pageId = upsertPage(url, document, existingPage);
            if (cacheHtmlEnabled) {
                savePageToLocalCache(pageId, document.outerHtml());
            }
            System.out.println("Crawled Successfully: " + url);
            return new CrawledPage(pageId, url, document);
        } catch (Exception ex) {
            System.err.println("Failed to crawl: " + url + ": " + ex.getMessage());
            return null;
        }
    }

    private static final String updatePageSQL = "UPDATE pages SET title = ?, last_modify_time = ?, content_size = ? WHERE id = ?";
    private static final String insertPageSQL = "INSERT OR IGNORE INTO pages(url, title, last_modify_time, content_size) VALUES (?, ?, ?, ?)";
    private int upsertPage(String url, Document document, ExistingPageInfo existingPage) {
        String lastModifyTime = extractLastModifyTime(document);
        int contentSize = extractContentSize(document);
        String title = document.title();

        if (existingPage != null) {
            db.update(updatePageSQL, title, lastModifyTime,contentSize, existingPage.id());
            return existingPage.id();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = db.update(connection -> {
            var statement = connection.prepareStatement(insertPageSQL, new String[]{"id"});
            statement.setString(1, url);
            statement.setString(2, title);
            statement.setString(3, lastModifyTime);
            statement.setInt(4, contentSize);
            return statement;
        }, keyHolder);

        if (rows == 0) throw new RuntimeException("Failed to insert page into database");

        return keyHolder.getKey().intValue();
    }

    private static final String queryExistingPageSQL = "SELECT id, last_modify_time FROM pages WHERE url = ? LIMIT 1";
    private ExistingPageInfo getExistingPageInfo(String url) {
        return db.query(queryExistingPageSQL,
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

    private final static String insertPendingLinkSQL = "INSERT INTO pending_links(page_id, outbound_link) VALUES (?, ?)";
    private void addChildLinksToPendingQueue(Document document, int currentPageId) {
        document.select("a[href]")
            .stream()
            .map(element -> normalizeUrl(element.absUrl("href")))
            .filter(url -> !url.isBlank())
            .forEach(url -> {
                if (!urlCrawlHistory.contains(url)) {
                    pendingCrawlUrls.offer(url);
                    urlCrawlHistory.add(url);
                }

                try {
                    db.update(insertPendingLinkSQL, currentPageId, url);
                } catch (Exception e) {}
            });
    }

    private String extractLastModifyTime(Document document) {
        String lastModifiedHeader = document.connection().response().header("Last-Modified");
        
        if (lastModifiedHeader == null || lastModifiedHeader.isBlank()) {
            return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        }

        return lastModifiedHeader;
    }

    private int extractContentSize(Document document) {
        String contentLengthHeader = document.connection().response().header("Content-Length");
        try {
            if (contentLengthHeader != null) throw new NumberFormatException();
            return Integer.parseInt(contentLengthHeader);
        } catch (NumberFormatException ex) {
            return document.outerHtml().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        String trimmed = rawUrl.trim();
        
        //如果缺少協議頭，預設使用 https
        if (!trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            trimmed = "https://" + trimmed;
        }

        // 移除 URL 中的 fragment 部分
        int fragmentIndex = trimmed.indexOf('#');
        trimmed = fragmentIndex >= 0 ? trimmed.substring(0, fragmentIndex) : trimmed;

        return trimmed;
    }

    private synchronized void resetSpider() {
        pendingCrawlUrls.clear();
        urlCrawlHistory.clear();
        remainingCrawlQuota = 0;
        cacheHtmlEnabled = false;
        running = false;
    }

    public int getTotalIndexedPages() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
        return count == null ? 0 : count;
    }

    public boolean isRunning() {
        return running;
    }
}