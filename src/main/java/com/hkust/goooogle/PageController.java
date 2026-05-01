package com.hkust.goooogle;

import com.hkust.goooogle.models.ExportedPage;
import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.models.Rankable;
import com.hkust.goooogle.models.Word;
import com.hkust.goooogle.services.KeywordService;
import com.hkust.goooogle.services.SearchService;
import com.hkust.goooogle.services.SpiderService;
import com.hkust.goooogle.utils.PaginationInfo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class PageController {

    private final SearchService searchService;
    private final SpiderService spiderService;
    private final KeywordService keywordService;

    private final static Pattern QuatedStringPattern = Pattern.compile("\"([^\"]*)\"");

    public PageController(SearchService searchService,
                          SpiderService spiderService,
                          KeywordService keywordService) {
        this.searchService = searchService;
        this.spiderService = spiderService;
        this.keywordService = keywordService;
    }

    @GetMapping({"/", "/home"})
    public String home(Model model) {
        model.addAttribute("pageTitle", "Home");
        return "home";
    }


@GetMapping("/search")
public String search(@RequestParam(value = "q") String query,
                     @RequestParam(value = "direct_search", required = false, defaultValue = "false") Boolean requreDirectSearch,
                     @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
                     Model model) {

    Matcher requireExactMatch = QuatedStringPattern.matcher(query);
    String cleanQuery = query.replaceAll("\"", "").trim();

    if (cleanQuery != null && !cleanQuery.isEmpty()) {
        long startTime = System.currentTimeMillis();

        Map<Integer, Float> ranking;
        int totalCount;
        boolean hasExactPhrase = requireExactMatch.find();
        
        if (hasExactPhrase) {
            // For exact match, get all results first
            String exactPhrase = requireExactMatch.group(1);
            
            // Get ALL matching pages (no limit)
            Map<Integer, Float> allRankings = searchService.search(cleanQuery, -1);
            
            // Total count before exact filtering
            int beforeFilterCount = allRankings.size();
            
            // Apply exact phrase filter
            ranking = searchService.excludeNonExactMatch(allRankings, exactPhrase);
            
            // Total count after exact filtering (for display)
            totalCount = ranking.size();
            
            // Limit to display limit
            Map<Integer, Float> limitedRanking = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<Integer, Float> entry : ranking.entrySet()) {
                if (count >= limit) break;
                limitedRanking.put(entry.getKey(), entry.getValue());
                count++;
            }
            ranking = limitedRanking;
            
            requireExactMatch.reset();
            
            System.out.println("Exact match: " + beforeFilterCount + " -> " + totalCount + " results");
        } else {
            // Non-exact match
            ranking = searchService.search(cleanQuery, limit);
            totalCount = searchService.getTotalMatchingCount(cleanQuery);
        }

        List<Rankable<Page>> pages = searchService.getPages(ranking);
        long elapsedMs = System.currentTimeMillis() - startTime;

        model.addAttribute("results", pages);
        model.addAttribute("resultCount", pages.size());      // Number displayed on page
        model.addAttribute("totalCount", totalCount);         // Total matching results!
        model.addAttribute("searchTimeMs", elapsedMs);
        model.addAttribute("displayLimit", limit);
    }

    model.addAttribute("pageTitle", "Search");
    model.addAttribute("exact", requireExactMatch);
    return "search";
}

    @GetMapping("/spider")
    public String spider(Model model) {
        model.addAttribute("pageTitle", "Spider");
        return "spider";
    }

    @PostMapping("/spider")
    public String startSpider(@RequestParam String url,
                              @RequestParam int maxPages,
                              Model model) {
        spiderService.startSpider(url, maxPages);
        model.addAttribute("pageTitle", "Spider");
        return "spider";
    }

    private static final int TOP_N_PAGES = 3;

    @GetMapping("/keywords")
    public String keywords(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sort", defaultValue = "count") String sort,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            Model model) {
        
        // 獲取總關鍵字數量以計算分頁信息
        int totalCount = keywordService.getTotalWordCount(q);
        PaginationInfo pagination = new PaginationInfo(totalCount, limit, offset);
        
        // 查詢當前頁的關鍵字列表
        List<Word> words = keywordService.listKeywords(q, sort, pagination.getLimit(), pagination.getOffset(), TOP_N_PAGES);
        
        // Add attributes to model
        model.addAttribute("pageTitle", "Keywords");
        model.addAttribute("words", words);
        model.addAttribute("q", q != null ? q : "");
        model.addAttribute("sort", sort);
        model.addAttribute("topNPages", TOP_N_PAGES);
        model.addAttribute("pagination", pagination);
        
        return "keywords";
    }

    @GetMapping("/spider/export")
    public ResponseEntity<String> exportDatabase() {
        List<ExportedPage> pages = spiderService.getAllIndexedPages(10, 10);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentDispositionFormData("attachment", "spider_result.txt");
        
        StringBuilder sb = new StringBuilder();
        for (ExportedPage page : pages) {
            sb.append(page.toExportingString(pages));
        }
        return ResponseEntity.ok().headers(headers).body(sb.toString());
    }
}
