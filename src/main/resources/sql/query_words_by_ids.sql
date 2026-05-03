SELECT
	i.value AS word,
	w.id AS word_id,
	COUNT(k.page_id) AS page_count
FROM json_each(?) i
JOIN words w ON w.word = i.value
LEFT JOIN keywords k ON k.word_id = w.id
GROUP BY i.key, i.value, w.id
ORDER BY i.key;
