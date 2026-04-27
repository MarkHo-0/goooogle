package com.hkust.goooogle.utils;

/**
 * Encapsulates pagination logic and calculations.
 * Handles validation, normalization, and computation of pagination parameters.
 */
public class PaginationInfo {
    
    private static final int MIN_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_PAGES_SHOW = 5;
    
    private final int limit;
    private final int offset;
    private final int totalCount;
    private final int totalPages;
    private final int currentPage;
    private final int paginationStartPage;
    private final int paginationEndPage;
    private final boolean showPreviousEllipsis;
    private final boolean showNextEllipsis;
    
    public PaginationInfo(int totalCount, int limit, int offset) {
        // Validate and normalize input parameters
        this.limit = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
        this.offset = Math.max(offset, 0);
        this.totalCount = Math.max(totalCount, 0);
        
        // Calculate pagination metrics
        this.totalPages = (this.totalCount + this.limit - 1) / this.limit;
        this.currentPage = this.totalPages > 0 ? this.offset / this.limit + 1 : 1;
        
        // Calculate pagination range (show 5 pages, current page in middle)
        PaginationRange range = calculatePaginationRange(this.totalPages, this.currentPage);
        this.paginationStartPage = range.startPage;
        this.paginationEndPage = range.endPage;
        this.showPreviousEllipsis = range.showPreviousEllipsis;
        this.showNextEllipsis = range.showNextEllipsis;
    }
    
    private PaginationRange calculatePaginationRange(int totalPages, int currentPage) {
        int startPage;
        int endPage;
        
        if (totalPages <= MAX_PAGES_SHOW) {
            startPage = 1;
            endPage = totalPages;
        } else {
            // Keep current page in the middle (pages: -2, -1, current, +1, +2)
            startPage = Math.max(1, currentPage - 2);
            endPage = Math.min(totalPages, currentPage + 2);
            
            // Adjust if near the start
            if (startPage == 1 && endPage < MAX_PAGES_SHOW) {
                endPage = MAX_PAGES_SHOW;
            }
            // Adjust if near the end
            if (endPage == totalPages && startPage > totalPages - MAX_PAGES_SHOW + 1) {
                startPage = totalPages - MAX_PAGES_SHOW + 1;
            }
        }
        
        boolean showPreviousEllipsis = startPage > 1;
        boolean showNextEllipsis = endPage < totalPages;
        
        return new PaginationRange(startPage, endPage, showPreviousEllipsis, showNextEllipsis);
    }
    
    // Getters
    public int getLimit() {
        return limit;
    }
    
    public int getOffset() {
        return offset;
    }
    
    public int getTotalCount() {
        return totalCount;
    }
    
    public int getTotalPages() {
        return totalPages;
    }
    
    public int getCurrentPage() {
        return currentPage;
    }
    
    public int getPaginationStartPage() {
        return paginationStartPage;
    }
    
    public int getPaginationEndPage() {
        return paginationEndPage;
    }
    
    public boolean isShowPreviousEllipsis() {
        return showPreviousEllipsis;
    }
    
    public boolean isShowNextEllipsis() {
        return showNextEllipsis;
    }
    
    /**
     * Helper class to encapsulate pagination range calculation results.
     */
    private static class PaginationRange {
        final int startPage;
        final int endPage;
        final boolean showPreviousEllipsis;
        final boolean showNextEllipsis;
        
        PaginationRange(int startPage, int endPage, boolean showPreviousEllipsis, boolean showNextEllipsis) {
            this.startPage = startPage;
            this.endPage = endPage;
            this.showPreviousEllipsis = showPreviousEllipsis;
            this.showNextEllipsis = showNextEllipsis;
        }
    }
}
