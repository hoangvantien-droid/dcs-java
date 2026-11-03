package com.dqh.dcs.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.dqh.dcs.web.Labels.AggregateDisplay;

/**
 * AggregateDisplayTest — tách nhãn/màu trạng thái tổng hợp theo loại từ chối
 * (thuần, rủi ro cao). Port test/views/labels-receipt.test.ts phần
 * aggregateDisplay: tranh_chap tách sai nội dung (đỏ) vs sai đăng ký (amber),
 * và hasPostAcceptContentError ƯU TIÊN cao nhất (IND-04a). Không cần DB.
 */
class AggregateDisplayTest {

    private final Labels labels = new Labels();

    @Test
    void tranhChapSaiNoiDungNhanDoNoiDungMauDo() {
        AggregateDisplay d = labels.aggregateDisplay("tranh_chap", true, false);
        assertEquals("Có site báo sai nội dung", d.label());
        assertEquals("status-thu_hoi", d.cls());
    }

    @Test
    void tranhChapChiSaiDangKyNhanDangKyMauAmber() {
        AggregateDisplay d = labels.aggregateDisplay("tranh_chap", false, false);
        assertEquals("Có site báo sai đăng ký", d.label());
        assertEquals("status-cho_xac_nhan", d.cls());
    }

    @Test
    void toanBoKhongPhuThuocCoHasContentReject() {
        AggregateDisplay a = labels.aggregateDisplay("toan_bo", true, false);
        AggregateDisplay b = labels.aggregateDisplay("toan_bo", false, false);
        assertEquals("Toàn bộ đã áp dụng", a.label());
        assertEquals("status-hieu_luc", a.cls());
        assertEquals(a.label(), b.label());
        assertEquals(a.cls(), b.cls());
    }

    @Test
    void motPhanDangChoChuaPhatHanhGiuNhanKhongPhuThuocCo() {
        assertEquals("Một phần đã áp dụng", labels.aggregateDisplay("mot_phan", true, false).label());
        assertEquals("Đang chờ phản hồi", labels.aggregateDisplay("dang_cho", true, false).label());
        assertEquals("Chưa phát hành", labels.aggregateDisplay("chua_phat_hanh", true, false).label());
    }

    @Test
    void hasPostAcceptContentErrorUuTienCaoNhatPhuLenMoiTrangThai() {
        assertEquals("Có site báo sai nội dung sau khi áp dụng",
                labels.aggregateDisplay("toan_bo", false, true).label());
        assertEquals("status-thu_hoi", labels.aggregateDisplay("toan_bo", false, true).cls());
        assertEquals("Có site báo sai nội dung sau khi áp dụng",
                labels.aggregateDisplay("mot_phan", false, true).label());
        assertEquals("Có site báo sai nội dung sau khi áp dụng",
                labels.aggregateDisplay("tranh_chap", true, true).label());
    }

    @Test
    void coPostAcceptFalseGiuHanhViCu() {
        assertEquals("Toàn bộ đã áp dụng", labels.aggregateDisplay("toan_bo", false, false).label());
        assertEquals("Toàn bộ đã áp dụng", labels.aggregateDisplay("toan_bo").label());
    }
}
