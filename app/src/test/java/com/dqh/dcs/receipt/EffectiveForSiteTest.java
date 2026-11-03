package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * EffectiveForSiteTest — ngày hiệu lực THỰC của một site (thuần, phương án 2a).
 * Tái hiện effectiveForSite trong src/domain/receipt.ts: ưu tiên ngày riêng của
 * site, nếu không thì về ngày hiệu lực gốc; null khi cả hai đều thiếu.
 */
class EffectiveForSiteTest {

    @Test
    void uuTienNgayRiengCuaSite() {
        assertEquals("2025-03-01",
                ReceiptService.effectiveForSite("2025-03-01", "2025-01-15"));
    }

    @Test
    void khongCoNgayRiengThiVeNgayGoc() {
        assertEquals("2025-01-15",
                ReceiptService.effectiveForSite(null, "2025-01-15"));
    }

    @Test
    void caHaiThieuThiNull() {
        assertNull(ReceiptService.effectiveForSite(null, null));
    }
}
