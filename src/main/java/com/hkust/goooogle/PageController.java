package com.hkust.goooogle;

import com.hkust.goooogle.models.Page;
import com.hkust.goooogle.services.KeywordService;
import com.hkust.goooogle.services.SearchService;
import com.hkust.goooogle.services.SpiderService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

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
    public String search(@RequestParam(value = "q", required = false) String q, Model model) {
        model.addAttribute("pageTitle", "Search");
        if (q != null && !q.isEmpty()) {
            List<Page> results = searchService.search(q, 20);
            model.addAttribute("results", results);
        }
        return "search";
    }

    @GetMapping("/spider")
    public String spider(Model model) {
        model.addAttribute("pageTitle", "Spider");
        model.addAttribute("totalPages", spiderService.getTotalIndexedPages());
        model.addAttribute("isRunning", spiderService.isRunning());
        return "spider";
    }

    @PostMapping("/spider")
    public String startSpider(@RequestParam String url,
                              @RequestParam int maxPages,
                              @RequestParam int batchSize,
                              @RequestParam int waitTime,
                              Model model) {
        spiderService.startSpider(url, maxPages, batchSize, waitTime);
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
}
