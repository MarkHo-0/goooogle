package com.hkust.goooogle.models;

import java.time.LocalDateTime;
import java.util.List;

public class Page {

    private String url;
    private LocalDateTime lastModifyTime;
    private int contentSize;
    private List<String> keywords;
    private List<PageSimple> childPages;

    public Page() {
    }

    public Page(String url,
                LocalDateTime lastModifyTime,
                int contentSize,
                List<String> keywords,
                List<PageSimple> childPages) {
        this.url = url;
        this.lastModifyTime = lastModifyTime;
        this.contentSize = contentSize;
        this.keywords = keywords;
        this.childPages = childPages;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public LocalDateTime getLastModifyTime() {
        return lastModifyTime;
    }

    public void setLastModifyTime(LocalDateTime lastModifyTime) {
        this.lastModifyTime = lastModifyTime;
    }

    public int getContentSize() {
        return contentSize;
    }

    public void setContentSize(int contentSize) {
        this.contentSize = contentSize;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public List<PageSimple> getChildPages() {
        return childPages;
    }

    public void setChildPages(List<PageSimple> childPages) {
        this.childPages = childPages;
    }
}