package com.dqh.dcs.doctypes;

import java.util.List;

/**
 * doctypes/DocTypes — nguồn DUY NHẤT danh sách loại tài liệu cho tầng ứng dụng
 * (port src/domain/doc-types.ts). Phải khớp CHECK documents.doc_type trong
 * migration 001 ('SOP','WI','Form','Policy','External').
 */
public final class DocTypes {

    private DocTypes() {
    }

    public record DocTypeOption(String code, String label) {
    }

    public static final List<DocTypeOption> DOC_TYPES = List.of(
            new DocTypeOption("SOP", "SOP"),
            new DocTypeOption("WI", "WI"),
            new DocTypeOption("Form", "Form"),
            new DocTypeOption("Policy", "Policy"),
            new DocTypeOption("External", "External"));

    public static final List<String> DOC_TYPE_CODES =
            DOC_TYPES.stream().map(DocTypeOption::code).toList();
}
