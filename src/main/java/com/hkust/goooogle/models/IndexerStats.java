package com.hkust.goooogle.models;

import java.util.function.Consumer;

@FunctionalInterface
interface StatsUpdater {
    void update(IndexerStats stats);
}

public class IndexerStats {

    private final Consumer<IndexerStats> updater;

    private int indexedPageCount = 0;
    private int pendingPageCount = 0; 
    private int indexedWordCount = 0;

    public IndexerStats(Consumer<IndexerStats> updater) {
        this.updater = updater;
        update();  // 初始化时立即更新一次统计信息
    }

    public void update() {
        updater.accept(this);
    }

    // 避免直接調用，而是通過 update() 方法來更新統計信息
    public void setStats(int indexedPages, int pendingPages, int indexedWords) {
        this.indexedPageCount = indexedPages;
        this.pendingPageCount = pendingPages;
        this.indexedWordCount = indexedWords;
    }

    public int getIndexedPageCount() {
        return indexedPageCount;
    }

    public int getPendingPageCount() {
        return pendingPageCount;
    }

    public int getIndexedWordCount() {
        return indexedWordCount;
    }

    public void printStats() {
        System.out.println("Total Indexed Pages: " + indexedPageCount);
        System.out.println("Total Pending Pages: " + pendingPageCount);
        System.out.println("Total Indexed Words: " + indexedWordCount);
    }
}
