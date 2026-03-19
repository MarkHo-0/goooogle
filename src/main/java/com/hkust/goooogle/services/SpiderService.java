package com.hkust.goooogle.services;

import org.springframework.stereotype.Service;

@Service
public class SpiderService {

    public boolean startSpider(String url, int maxPages, int batchSize, int betweenBatchesDelay) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public int getTotalIndexedPages() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public boolean isRunning() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}