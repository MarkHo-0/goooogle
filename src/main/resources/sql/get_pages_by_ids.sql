-- 提供順序和頁面ID的輸入
WITH input_ids AS (
    SELECT
        j.key + 1 AS ord,
        j.value   AS page_id
    FROM json_each(?) j
),

-- 提取每個頁面的基本資訊
page_meta AS (
    SELECT
        i.ord,
        i.page_id,
        p.url,
        p.title,
        p.last_modify_time,
        p.content_size
    FROM input_ids i
    LEFT JOIN pages p ON p.id = i.page_id
),

-- 提取每個頁面所有關鍵字的統計資訊
page_kw AS (
    SELECT
        i.page_id,
        w.word,
        k.body_count,
        k.title_count,
        ROW_NUMBER() OVER (PARTITION BY i.page_id ORDER BY k.weighted_count DESC) AS rn
    FROM keywords k
    JOIN words w on w.id = k.word_id
    JOIN input_ids i ON i.page_id = k.page_id
),

-- 基於上面的統計，只保留前5個關鍵字
ranked_page_kw AS (
    SELECT
        page_id,
        json_group_array(
            json_object(
                'word', word,
                'bodyCount', body_count,
                'titleCount', title_count
            )
        ) AS top_N_Keywords
    FROM page_kw
    WHERE rn <= ?
    GROUP BY page_id
),

-- 提取每個頁面的父頁面資訊
parent_page AS (
    SELECT
        i.page_id,
        json_group_array(
            json_object(
                'title', p.title,
                'url', p.url
            )
        ) AS parent_pages
    FROM links l
    JOIN input_ids i ON i.page_id = l.child_page_id
    JOIN pages p ON p.id  = l.parent_page_id
    GROUP BY i.page_id
),

-- 提取每個頁面的子頁面資訊，除了查詢links，還查詢 pending_links，以確保尚未索引的頁面也能被包含在內
child_page AS (
    WITH all_relations AS (
        -- 已索引的頁面
        SELECT 
            i.page_id AS pid, 
            l.child_page_id AS cid,
            NULL AS url
        FROM input_ids i
        JOIN links l ON l.parent_page_id = i.page_id

        UNION ALL

        -- 尚未索引的頁面
        SELECT 
            i.page_id AS pid, 
            NULL AS cid,
            pl.outbound_link AS url
        FROM input_ids i
        JOIN pending_links pl ON pl.page_id = i.page_id
    )
    SELECT
        r.pid AS page_id,
        json_group_array(
            json_object(
                'title', IFNULL(p.title, 'UNINDEXED'),
                'url', IFNULL(p.url, r.url)
            )
        ) AS child_pages
    FROM all_relations r
    LEFT JOIN pages p ON r.cid = p.id
    GROUP BY r.pid
)

-- 將所有資訊整合在一起
SELECT
    m.page_id,
    m.url,
    m.title,
    m.last_modify_time,
    m.content_size,
    IFNULL(k.top_N_Keywords, '[]') AS top_N_Keywords,
    IFNULL(pp.parent_pages, '[]') AS parent_pages,
    IFNULL(cp.child_pages, '[]') AS child_pages
FROM page_meta m
LEFT JOIN ranked_page_kw k ON k.page_id = m.page_id
LEFT JOIN parent_page pp ON pp.page_id = m.page_id
LEFT JOIN child_page cp ON cp.page_id = m.page_id
ORDER BY m.ord;