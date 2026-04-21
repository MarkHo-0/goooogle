package com.hkust.goooogle;

import com.hkust.goooogle.models.ExportedPage;
import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.services.KeywordService;
import com.hkust.goooogle.services.SearchService;
import com.hkust.goooogle.services.SpiderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
public class PageController {

    private final SearchService searchService;
    private final SpiderService spiderService;
    private final KeywordService keywordService;

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
public String search(@RequestParam(value = "q", required = false) String q, 
                     @RequestParam(value = "exact", required = false) String exact, 
                     Model model) {
    model.addAttribute("pageTitle", "Search");
    boolean isExactMatch = !"false".equals(exact);
    model.addAttribute("exactMatch", isExactMatch ? "true" : "false");

    if (q != null && !q.isEmpty()) {
        long startTime = System.currentTimeMillis();

        Map<Integer, Float> ranking = searchService.search(q, 10);
        
        Map<Integer, Float> finalRanking;
        if (isExactMatch) {
            finalRanking = searchService.excludeNonExactMatch(ranking, q);
        } else {
            finalRanking = ranking;
        }

        List<Page> pages = searchService.getPages(finalRanking);
        long elapsedMs = System.currentTimeMillis() - startTime;

        model.addAttribute("results", pages);
        model.addAttribute("resultCount", pages.size());
        model.addAttribute("searchTimeMs", elapsedMs);
    }
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

    @GetMapping("/keywords")
    public String keywords(@RequestParam(value = "q", required = false) String q, Model model) {
        model.addAttribute("pageTitle", "Keywords");
        List<String> keywords;
        if (q != null && !q.isEmpty()) {
            keywords = keywordService.queryKeywords(q, 50, 0);
        } else {
            keywords = keywordService.listKeywords(50, 0);
        }
        model.addAttribute("keywords", keywords);
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
