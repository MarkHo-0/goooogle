SELECT 
    t.word,
    SUM(t.weighted_count) AS total_count,
    json_group_array(
        json_object('url', t.url, 'title', t.title)
    ) AS pages
FROM (
    SELECT 
        w.id,
        w.word,
        k.page_id,
        p.url,
        p.title,
        k.body_count + k.title_count AS weighted_count,
        ROW_NUMBER() OVER (PARTITION BY w.id ORDER BY k.body_count + k.title_count DESC) AS rn
    FROM words w
    LEFT JOIN keywords k ON w.id = k.word_id
    LEFT JOIN pages p ON k.page_id = p.id
    WHERE w.word LIKE ?
) t
WHERE rn <= ? OR rn IS NULL
GROUP BY t.id, t.word
