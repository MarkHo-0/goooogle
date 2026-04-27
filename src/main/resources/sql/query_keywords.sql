SELECT
    w.word AS word,
    COUNT(k.page_id) AS page_count,
    COALESCE(SUM(k.body_count + k.title_count), 0) AS total_occurrences,
    COALESCE(SUM(k.weighted_count), 0) AS total_weight
FROM words w
LEFT JOIN keywords k ON k.word_id = w.id
WHERE LOWER(w.word) LIKE ? ESCAPE '\'
    AND
    (? NOT IN ('alpha_asc', 'alpha_desc') OR SUBSTR(w.word, 1, 1) NOT GLOB '[0-9]')
    AND
    (? NOT IN ('num_asc', 'num_desc') OR SUBSTR(w.word, 1, 1) GLOB '[0-9]')
GROUP BY w.id, w.word
ORDER BY
    CASE WHEN ? = 'popularity_desc' THEN total_occurrences END DESC,
    CASE WHEN ? = 'popularity_asc' THEN total_occurrences END ASC,
    CASE WHEN ? = 'alpha_desc' THEN w.word END DESC,
    CASE WHEN ? = 'alpha_asc' THEN w.word END ASC,
    CASE WHEN ? = 'num_desc' THEN CAST(w.word AS INTEGER) END DESC,
    CASE WHEN ? = 'num_asc' THEN CAST(w.word AS INTEGER) END ASC,
    CASE WHEN ? = 'coverage_desc' THEN page_count END DESC,
    CASE WHEN ? = 'coverage_asc' THEN page_count END ASC,
    w.word ASC
LIMIT ? OFFSET ?;