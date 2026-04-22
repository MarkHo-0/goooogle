package com.hkust.goooogle.models;

public record Keyword(
    String word,
    int bodyCount,
    int titleCount
) {
    public int totalCount() {
        return bodyCount + titleCount;
    }
}
