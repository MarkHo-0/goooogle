package com.hkust.goooogle;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class PageController {

    @GetMapping({"/", "/home"})
    public String home(Model model) {
        model.addAttribute("pageTitle", "Home");
        return "home";
    }

    @GetMapping("/search")
    public String search(@RequestParam(value = "q", required = false) String q, Model model) {
        model.addAttribute("pageTitle", "Search");
        // TODO: 查詢資料庫並返回真正的搜尋結果
        if (q != null && !q.isEmpty()) {
            model.addAttribute("results", List.of("Result 1 for '" + q + "'"));
        }
        return "search";
    }

    @GetMapping("/spider")
    public String spider(Model model) {
        model.addAttribute("pageTitle", "Spider");
        // TODO: 顯示爬蟲狀態和統計數據
        model.addAttribute("totalPages", 0);
        return "spider";
    }

    @PostMapping("/spider")
    public String startSpider(@RequestParam String url,
                              @RequestParam int maxPages,
                              @RequestParam int batchSize,
                              @RequestParam int waitTime,
                              Model model) {
                    model.addAttribute("pageTitle", "Spider");
        // TODO: 啟動爬蟲並更新爬蟲狀態
        model.addAttribute("totalPages", 0);
        return "spider";
    }

    @GetMapping("/keywords")
    public String keywords(@RequestParam(value = "q", required = false) String q, Model model) {
        model.addAttribute("pageTitle", "Keywords");
        // TODO: 從資料庫獲取關鍵字並顯示
        List<String> keywords;
        if (q != null && !q.isEmpty()) {
            keywords = List.of("Keyword for '" + q + "'");
        } else {
            keywords = List.of("Keyword1", "Keyword2", "Keyword3");
        }
        model.addAttribute("keywords", keywords);
        return "keywords";
    }
}
