package com.hkust.goooogle.models;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.RowMapper;

public record ExportedPage(
    int id,
    String url,
    String title,
    String lastModifyTime,
    Integer contentByteSize,
    String keywords,
    String keyword_totalCounts,
    String childLinkIdxs
) {

    public static final RowMapper<ExportedPage> sqlMapper = (rs, rowNum) -> new ExportedPage( 
        rs.getInt("id"),
        rs.getString("url"),
        rs.getString("title"),
        rs.getString("last_modify_time"),
        rs.getInt("content_size"),
        rs.getString("keywords"),
        rs.getString("total_counts"),
        rs.getString("childs")
    );
    
    public String toExportingString(List<ExportedPage> allPages) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        sb.append(url).append("\n");
        sb.append(lastModifyTime).append(", ").append(contentByteSize).append(" bytes\n");

        String[] wordsRaw = keywords.split(",");
        String[] countsRaw = keyword_totalCounts.split(",");
        if (wordsRaw.length > 0 && wordsRaw.length == countsRaw.length) {
            sb.append(IntStream.range(0, wordsRaw.length)
                .mapToObj(i -> wordsRaw[i] + " " + countsRaw[i])
                .collect(Collectors.joining("; "))
            ).append("\n");
        }

        String[] childIdxsRaw = childLinkIdxs.split(",");
        for (String idxStr : childIdxsRaw) {
            int idx = Integer.parseInt(idxStr);
            ExportedPage childPage = allPages.stream().filter(p -> p.id() == idx).findFirst().orElse(null);
            if (childPage == null) continue;
            sb.append(childPage.url()).append("\n");
        }

        return sb.append("\n").toString();
    }
}
