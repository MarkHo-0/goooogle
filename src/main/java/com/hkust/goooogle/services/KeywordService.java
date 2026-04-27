package com.hkust.goooogle.services;

import com.hkust.goooogle.annotations.LoadSql;
import com.hkust.goooogle.models.KeywordBrowseItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KeywordService {
    private final JdbcTemplate db;

    public KeywordService(JdbcTemplate jdbcTemplate) {
        this.db = jdbcTemplate;
    }

    @LoadSql("sql/browse_keywords.sql")
    private String sqlFile_BrowseKeywords;

    @LoadSql("sql/query_keywords.sql")
    private String sqlFile_QueryKeywords;

    public List<KeywordBrowseItem> listKeywords(String sort, int limit, int offset) {
        String resolvedSort = resolveSort(sort);
        return db.query(
            sqlFile_BrowseKeywords,
            KeywordBrowseItem.sqlMapper,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            limit,
            offset
        );
    }

    public List<String> listKeywords(int limit, int offset) {
        return listKeywords("popularity_desc", limit, offset)
            .stream()
            .map(KeywordBrowseItem::word)
            .toList();
    }

    public List<KeywordBrowseItem> queryKeywords(String query, String sort, int limit, int offset) {
        String resolvedSort = resolveSort(sort);
        String likePattern = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase()) + "%";
        return db.query(
            sqlFile_QueryKeywords,
            KeywordBrowseItem.sqlMapper,
            likePattern,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            resolvedSort,
            limit,
            offset
        );
    }

    public List<String> queryKeywords(String query, int limit, int offset) {
        return queryKeywords(query, "popularity_desc", limit, offset)
            .stream()
            .map(KeywordBrowseItem::word)
            .toList();
    }

    public Map<String, String> sortOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("popularity_desc", "No of occurence: high to low");
        options.put("popularity_asc", "No of occurence: low to high");
        options.put("alpha_asc", "Alphabetical: A to Z");
        options.put("alpha_desc", "Alphabetical: Z to A");
        options.put("num_asc", "Numbers: 0 to 9");
        options.put("num_desc", "Numbers: 9 to 0");
        options.put("coverage_desc", "Most pages covered");
        options.put("coverage_asc", "Fewest pages covered");
        return options;
    }

    public String keywordPickerScript() {
        return """
            (() => {
                const form = document.getElementById('keywordSearchForm');
                const hiddenQuery = document.getElementById('keywordVectorQuery');
                const selectedStateInput = document.getElementById('keywordSelectedState');
                const selectedLabel = document.getElementById('keywordSelectionLabel');
                const selectedList = document.getElementById('keywordSelectedList');
                const submitButton = document.getElementById('keywordSubmitButton');
                if (!form || !hiddenQuery || !selectedLabel || !selectedList || !submitButton || !selectedStateInput) {
                    return;
                }

                const checkboxes = Array.from(document.querySelectorAll('.keyword-checkbox'));
                const checkboxByWord = new Map(checkboxes.map((checkbox) => [checkbox.value, checkbox]));

                const parseSelectedWords = (raw) => {
                    const unique = new Set();
                    return (raw || '')
                        .split(',')
                        .map((word) => word.trim())
                        .filter((word) => {
                            if (!word || unique.has(word)) {
                                return false;
                            }
                            unique.add(word);
                            return true;
                        });
                };

                let selectedWords = parseSelectedWords(selectedStateInput.value);

                // Reflect previously-selected words in currently-visible checkboxes.
                checkboxes.forEach((checkbox) => {
                    checkbox.checked = selectedWords.includes(checkbox.value);
                });

                const persistSelectedState = () => {
                    selectedStateInput.value = selectedWords.join(',');
                    hiddenQuery.value = selectedWords.join(' ');
                    selectedLabel.textContent = selectedWords.length === 0 ? 'None' : '';
                    submitButton.disabled = selectedWords.length === 0;
                };

                const removeSelectedWord = (word) => {
                    const index = selectedWords.indexOf(word);
                    if (index < 0) {
                        return;
                    }
                    selectedWords.splice(index, 1);
                    const checkbox = checkboxByWord.get(word);
                    if (checkbox) {
                        checkbox.checked = false;
                    }
                    renderSelection();
                };

                const renderSelection = () => {
                    selectedList.innerHTML = '';
                    selectedWords.forEach((word) => {
                        const chip = document.createElement('span');
                        chip.className = 'keyword-selected-chip';

                        const chipText = document.createElement('span');
                        chipText.textContent = word;
                        chip.appendChild(chipText);

                        const removeButton = document.createElement('button');
                        removeButton.type = 'button';
                        removeButton.className = 'keyword-selected-chip-remove';
                        removeButton.textContent = 'x';
                        removeButton.setAttribute('aria-label', `Remove ${word}`);
                        removeButton.addEventListener('click', () => removeSelectedWord(word));
                        chip.appendChild(removeButton);

                        selectedList.appendChild(chip);
                    });

                    persistSelectedState();
                };

                const syncFromCheckboxes = () => {
                    checkboxes.forEach((checkbox) => {
                        const index = selectedWords.indexOf(checkbox.value);
                        if (checkbox.checked && index < 0) {
                            selectedWords.push(checkbox.value);
                        }
                        if (!checkbox.checked && index >= 0) {
                            selectedWords.splice(index, 1);
                        }
                    });
                    renderSelection();
                };

                checkboxes.forEach((checkbox) => checkbox.addEventListener('change', syncFromCheckboxes));
                renderSelection();
            })();
            """;
    }

    private static String resolveSort(String sort) {
        return switch (sort == null ? "" : sort) {
            case "popularity_asc", "alpha_asc", "alpha_desc", "num_asc", "num_desc",
                 "coverage_desc", "coverage_asc" -> sort;
            default -> "popularity_desc";
        };
    }

    private static String escapeLike(String query) {
        return query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }
}