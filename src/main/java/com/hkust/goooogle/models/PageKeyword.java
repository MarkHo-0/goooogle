package com.hkust.goooogle.models;

public record PageKeyword(
    String word,
    int bodyCount,
    int titleCount
) {
    public int totalCount() {
        return bodyCount + titleCount;
    }
}
