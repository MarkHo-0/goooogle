package com.hkust.goooogle.models;

public record PageKeyword(
    String word,
    int bodyCount,
    int titleCount,
    int totalCount
) {
}
