package com.hkust.goooogle.models;

public record Rankable<T>(
    T item,
    double similarityScore
) {

}
