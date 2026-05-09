WITH input_word_ids AS (
	SELECT
		j.key AS ord,
		j.value AS word_id
	FROM json_each(?) AS j
)
SELECT
    k.page_id,
    p.max_term_count,
	json_group_array(
        json_object(
            'word', w.word,
            'bodyCount', k.body_count,
            'titleCount', k.title_count,
            'totalCount', k.total_count
        ) ORDER BY i.ord
    ) AS keyword_weights
FROM keywords k
JOIN words w ON k.word_id = w.id
JOIN pages p ON k.page_id = p.id
JOIN input_word_ids i ON i.word_id = k.word_id
GROUP BY k.page_id, p.max_term_count
HAVING COUNT(*) = (SELECT COUNT(*) FROM input_word_ids)
ORDER BY k.page_id;
