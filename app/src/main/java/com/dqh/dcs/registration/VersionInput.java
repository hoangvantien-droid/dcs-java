package com.dqh.dcs.registration;

/**
 * registration/VersionInput — dữ liệu tạo document_versions
 * (port src/domain/registration.ts VersionInput). changeSignificance: 'lon'|'nho'.
 */
public record VersionInput(
        String docId,
        String versionNo,
        String title,
        String issuedDate,
        String externalEffectiveDate,
        String effectiveDate,
        int reviewCycleMonths,
        String expiryDate,
        String sourceChannel,
        String filePath,
        String fileHash,
        String registrantUserId,
        String ownerDepartmentId,
        String supersedesVersionId,
        String changeSignificance,
        String changeSummary) {
}
