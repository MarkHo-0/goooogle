package com.hkust.goooogle.models;

// 一個輕便的 Page, 只有在被索引後才會有 title 和 id
public record RefPage(
    String url,
    String title
) {

}
