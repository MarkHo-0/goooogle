WITH input_word_ids AS (
	SELECT
		j.key AS ord,
		j.value AS word_id
	FROM json_each(?) AS j
)
SELECT
    k.page_id,
	json_group_array(k.weighted_count ORDER BY i.ord) AS weighted_counts
FROM keywords k
JOIN input_word_ids i ON i.word_id = k.word_id
GROUP BY page_id
HAVING COUNT(*) = (SELECT COUNT(*) FROM input_word_ids)
ORDER BY page_id;
