package com.dqh.dcs.review;

/**
 * review/ReviewRow — một dòng tồn đọng rà soát (port src/domain/review.ts ReviewRow).
 */
public record ReviewRow(
        String reviewId,
        String docId,
        String versionNo,
        String title,
        String dueDate,
        String status,
        String verdict,
        String verdictNote,
        String departmentName,
        String scopeLevel,
        String scopeBuId,
        String scopeSiteId,
        String scopeDeptId,
        int soNgayQuaHan) {
}
