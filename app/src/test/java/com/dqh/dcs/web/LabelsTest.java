package com.dqh.dcs.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * LabelsTest — ánh xạ mã nghiệp vụ → nhãn tiếng Việt (thuần). Port
 * test/views/labels-receipt.test.ts: mã lạ trả về CHÍNH mã (lộ sớm khi test),
 * null/rỗng → chuỗi rỗng, giữ nhãn vai mới/cũ. KHÔNG đổi giá trị enum. Không DB.
 */
class LabelsTest {

    private final Labels labels = new Labels();

    @Test
    void docStatusNhanNguoiDoc() {
        assertEquals("Đã ban hành", labels.docStatusLabel("da_ban_hanh"));
        assertEquals("Đã thu hồi", labels.docStatusLabel("thu_hoi"));
        assertEquals("Hết hiệu lực", labels.docStatusLabel("het_hieu_luc"));
    }

    @Test
    void docStatusMaLaTraVeChinhMa() {
        assertEquals("xyz", labels.docStatusLabel("xyz"));
    }

    @Test
    void docStatusNullRongLaChuoiRong() {
        assertEquals("", labels.docStatusLabel(null));
        assertEquals("", labels.docStatusLabel(""));
    }

    @Test
    void roleVaiMoiVaVaiCu() {
        assertTrue(labels.roleLabel("DC_SITE_DOC_CONTROL").contains("Kiểm soát tài liệu"));
        assertTrue(labels.roleLabel("DC_DEPT_HEAD").contains("vai cũ"));
    }

    @Test
    void receiptStatus() {
        assertEquals("Chờ phản hồi", labels.receiptStatusLabel("cho_phan_hoi"));
        assertEquals("Đã chấp nhận", labels.receiptStatusLabel("chap_nhan"));
        assertEquals("Đã từ chối", labels.receiptStatusLabel("tu_choi"));
        assertEquals("zzz", labels.receiptStatusLabel("zzz"));
    }

    @Test
    void receiptRejectTypePhanBiet() {
        assertEquals("Sai thông tin đăng ký", labels.receiptRejectTypeLabel("sai_dang_ky"));
        assertEquals("Sai nội dung tài liệu", labels.receiptRejectTypeLabel("sai_noi_dung"));
    }

    @Test
    void issueReportStatus() {
        assertTrue(labels.issueReportStatusLabel("moi").contains("Mới"));
        assertTrue(labels.issueReportStatusLabel("chuyen_phan_hoi").contains("chuyển"));
        assertTrue(labels.issueReportStatusLabel("bac_bo").contains("bác bỏ"));
    }

    @Test
    void aggregateStatusCuKhongDoi() {
        assertEquals("Chưa phát hành", labels.aggregateStatusLabel("chua_phat_hanh"));
        assertEquals("Đang chờ phản hồi", labels.aggregateStatusLabel("dang_cho"));
        assertEquals("Một phần đã áp dụng", labels.aggregateStatusLabel("mot_phan"));
        assertEquals("Có site báo sai sót", labels.aggregateStatusLabel("tranh_chap"));
        assertEquals("Toàn bộ đã áp dụng", labels.aggregateStatusLabel("toan_bo"));
    }
}
