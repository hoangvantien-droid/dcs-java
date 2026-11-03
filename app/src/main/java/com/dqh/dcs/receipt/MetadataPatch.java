package com.dqh.dcs.receipt;

/**
 * receipt/MetadataPatch — vá metadata khi sửa đăng ký tại chỗ (Loại 1)
 * (port MetadataPatch trong src/domain/receipt.ts).
 *
 * expiryDate: null = giữ nguyên; "" = xóa (NULL); giá trị = ghi đè (điểm 10).
 */
public record MetadataPatch(
        String title,
        String issuedDate,
        String effectiveDate,
        Integer reviewCycleMonths,
        String expiryDate,
        String ownerDepartmentId) {
}
