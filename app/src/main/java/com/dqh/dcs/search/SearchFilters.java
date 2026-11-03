package com.dqh.dcs.search;

import java.util.List;

/**
 * search/SearchFilters — bộ lọc đa chiều cho tìm kiếm (port src/domain/search.ts SearchFilters).
 * Các trường rỗng/null nghĩa là bỏ lọc.
 */
public record SearchFilters(
        List<String> docTypes,
        String origin,
        String scopeLevel,
        String effFrom,
        String effTo,
        List<String> matchLevels,
        String sort,
        String buId,
        String siteId,
        String deptId) {

    public static SearchFilters empty() {
        return new SearchFilters(null, null, null, null, null, null, null, null, null, null);
    }
}
