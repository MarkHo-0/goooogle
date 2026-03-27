WITH 
    -- 處理子頁面 - 不需排序，僅取前 N 個
    ranked_links AS (
        SELECT parent_page_id, child_page_id,
        ROW_NUMBER() OVER (PARTITION BY parent_page_id) AS rn
        FROM links
    ),
    link_summary AS (
        SELECT parent_page_id, GROUP_CONCAT(child_page_id) AS childs
        FROM ranked_links WHERE rn <= ? GROUP BY parent_page_id
    ),
    -- 處理關鍵字 - 需按總出現次數排序，取前 N 個
    ranked_keywords AS (
        SELECT k.page_id, w.word, (k.body_count + k.title_count) AS total_count,
        ROW_NUMBER() OVER (
            PARTITION BY k.page_id 
            ORDER BY (k.body_count + k.title_count) DESC
        ) AS rn
        FROM keywords k JOIN words w ON k.word_id = w.id
    ),
    keyword_summary AS (
        SELECT page_id, GROUP_CONCAT(word) AS keywords, GROUP_CONCAT(total_count) AS total_counts
        FROM ranked_keywords WHERE rn <= ? GROUP BY page_id
    )
-- 主查詢
SELECT p.*, ls.childs, ks.keywords, ks.total_counts
FROM pages p
LEFT JOIN link_summary ls ON p.id = ls.parent_page_id
LEFT JOIN keyword_summary ks ON p.id = ks.page_id
ORDER BY p.id ASC;