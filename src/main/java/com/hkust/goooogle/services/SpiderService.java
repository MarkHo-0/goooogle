package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.ExportedPage;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@Service
public class SpiderService {
    private final JdbcTemplate db;
    private final IndexerService indexerService;
    private final Queue<String> pendingCrawlUrls = new LinkedList<>();
    private final List<String> urlCrawlHistory = new ArrayList<>();
    private int remainingCrawlQuota = 0;

    private volatile boolean running = false;

    private record ExistingPageInfo(int id, String lastModifyTime) {}
    private record CrawledPage(int pageId, String url, Document document) {}

    public SpiderService(JdbcTemplate jdbcTemplate, IndexerService indexerService) {
        this.db = jdbcTemplate;
        this.indexerService = indexerService;
    }

    public synchronized boolean startSpider(String url, int maxPages) {
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
        pendingCrawlUrls.offer(normalizedStartUrl);
        running = true;

        Thread worker = new Thread(this::executeCrawlingAndIndexing, "spider-main-thread");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void executeCrawlingAndIndexing() {
        long startTime = System.currentTimeMillis();
        
        while (remainingCrawlQuota > 0) {
            String nextUrl = pendingCrawlUrls.poll();
            if (nextUrl == null) break;

            CrawledPage crawledPage = crawlPage(nextUrl);
            if (crawledPage == null) continue;

            addChildLinksToPendingQueue(crawledPage.document(), crawledPage.pageId());

            indexerService.indexPage(crawledPage.pageId(), crawledPage.url(), crawledPage.document());

            urlCrawlHistory.add(crawledPage.url());
            remainingCrawlQuota--;
        }

        if (!urlCrawlHistory.isEmpty()) {
            indexerService.resolvePendingLinksFromAllPages();
        }
        
        indexerService.getStats().update();

        resetSpider();
        
        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.println("Spider completed in " + String.format("%.2f", duration) + " seconds");
    }

    private CrawledPage crawlPage(String url) {
        try {
            ExistingPageInfo existingPage = getExistingPageInfo(url);
            Connection connection = Jsoup.connect(url).followRedirects(true).ignoreHttpErrors(true).ignoreContentType(true);

            // 如果數據庫中有舊紀錄，則在請求頭添加該紀錄的時間，讓對方伺服器檢查是否需要更新索引
            if (existingPage != null && existingPage.lastModifyTime() != null && !existingPage.lastModifyTime().isBlank()) {
                connection.header("If-Modified-Since", existingPage.lastModifyTime());
            }

            // 下載頁面
            Connection.Response response = connection.execute();

            if (response.statusCode() >= 400) {
                System.out.println("Failed to crawl (HTTP " + response.statusCode() + "): " + url);
                return null;
            }

            if (response.statusCode() == 304) {
                System.out.println("Skipped (The target indicates no update): " + url);
                return null;
            }

            if (existingPage != null && existingPage.lastModifyTime().equals(response.header("Last-Modified"))) {
                System.out.println("Skipped (The target's last modified time is the same): " + url);
                return null;
            }

            // 如果有舊紀錄且伺服器返回內容，代表網站更新了，因此要重新索引網站
            if (existingPage != null && response.body() != null) {
                // 因為有 delele cascaded，所以可以只刪除page，資料庫自動刪除其它關聯的資料
                db.update("DELETE FROM pages WHERE id = ?", existingPage.id());
                System.out.println("Removed outdated page: " + url);
            }

            // 解析頁面
            Document document = response.parse();
            int pageId = insertPage(url, document);

            return new CrawledPage(pageId, url, document);
        } catch (Exception ex) {
            System.err.println("Failed to crawl: " + url + ": " + ex.getMessage());
            return null;
        }
    }

    private static final String insertPageSQL = "INSERT INTO pages(url, title, last_modify_time, content_size, full_page) VALUES (?, ?, ?, ?, ?)";
    private int insertPage(String url, Document document) {
        String lastModifyTime = extractLastModifyTime(document);
        int contentSize = extractContentSize(document);
        String title = document.title();
        String bodyText = document.body().text();
        // Store as: plain title + unique divider + plain body (HTML tags already stripped by Jsoup .text())
        String fullPage = title + "\u001F" + bodyText;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = db.update(connection -> {
            var statement = connection.prepareStatement(insertPageSQL, new String[]{"id"});
            statement.setString(1, url);
            statement.setString(2, title);
            statement.setString(3, lastModifyTime);
            statement.setInt(4, contentSize);
            statement.setString(5, fullPage);
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

    private final static String insertPendingLinkSQL = "INSERT INTO pending_links(page_id, outbound_link) VALUES (?, ?)";
    private void addChildLinksToPendingQueue(Document document, int currentPageId) {
        document.select("a[href]")
            .stream()
            .map(element -> normalizeUrl(element.absUrl("href")))
            .filter(url -> !url.isBlank())
            .forEach(url -> {
                if (!urlCrawlHistory.contains(url)) {
                    pendingCrawlUrls.offer(url);
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

        // 將最後的斜杠移除
        trimmed = trimmed.replaceAll("/+$", "");

        return trimmed;
    }

    private synchronized void resetSpider() {
        pendingCrawlUrls.clear();
        urlCrawlHistory.clear();
        remainingCrawlQuota = 0;
        running = false;
    }

    public int getTotalIndexedPages() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM pages", Integer.class);
        return count == null ? 0 : count;
    }

    public boolean isRunning() {
        return running;
    }

    @LoadSql("sql/query_for_export.sql")
    private String sqlFile_QueryForExport;
    public List<ExportedPage> getAllIndexedPages(int childLinkLimit, int keywordLimit) {
        return db.query(sqlFile_QueryForExport, ExportedPage.sqlMapper, childLinkLimit, keywordLimit);
    }
}