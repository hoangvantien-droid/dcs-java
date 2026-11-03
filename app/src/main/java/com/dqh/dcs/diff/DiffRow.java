package com.dqh.dcs.diff;

/**
 * diff/DiffRow — một dòng khác biệt giữa hai phiên bản (port src/domain/diff.ts DiffRow).
 * change_type: them | xoa | doi_noi_dung | doi_tieu_de | khong_doi.
 */
public record DiffRow(
        String sectionCode,
        String changeType,
        String oldTitle,
        String newTitle,
        String oldText,
        String newText) {
}
