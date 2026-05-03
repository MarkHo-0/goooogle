WITH input_word_ids AS (
	SELECT value AS word_id
	FROM json_each(?)
)
SELECT
	k.page_id,
	json_group_array(k.weighted_count) AS weighted_counts
FROM keywords k
JOIN input_word_ids i ON i.word_id = k.word_id
GROUP BY k.page_id
HAVING COUNT(DISTINCT k.word_id) = (SELECT COUNT(*) FROM input_word_ids)
ORDER BY k.page_id;
