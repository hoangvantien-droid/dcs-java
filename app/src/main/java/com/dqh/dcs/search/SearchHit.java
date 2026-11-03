package com.dqh.dcs.search;

/**
 * search/SearchHit — một kết quả tìm kiếm (port src/domain/search.ts SearchHit).
 */
public record SearchHit(
        String docId,
        String versionId,
        String versionNo,
        String title,
        String status,
        String sectionCode,
        String sectionTitle,
        String snippet,
        String matchLevel,
        double rank,
        String scopeLevel,
        String effectiveDate,
        String nextReviewDate,
        String ownerDepartmentId,
        String departmentName,
        String origin) {
}
