package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.dqh.dcs.common.Clock;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * UatReceiptFlowIT — DC Site chấp nhận/từ chối phiếu qua HTTP THẬT.
 *   UAT-30: accept → chap_nhan, apply_date = ngày hiệu lực site (= hôm nay).
 *   UAT-31 [BẢO MẬT]: accept TRƯỚC ngày hiệu lực site → receipt.before_effective.
 *   UAT-32/80 [BẢO MẬT]: DC Site site khác mở phiếu → 403 (R010 scope site).
 *   UAT-33: reject sai_dang_ky → tu_choi, hiện ở trang chủ Author.
 *   UAT-34 [BẢO MẬT]: reject loại không hợp lệ → 400.
 * Fixture tạo qua ItServices (cùng DB seed); hành vi kiểm qua HTTP.
 */
class UatReceiptFlowIT extends UatHttpBase {

    private final List<String> createdDocs = new java.util.ArrayList<>();
    private ItServices svc;

    @AfterEach
    void tearDown() {
        for (String d : createdDocs) {
            DbSupport.cleanupDoc(d);
        }
    }

    private ItServices svc() {
        if (svc == null) {
            svc = new ItServices();
        }
        return svc;
    }

    /** Đăng ký bản cho site0 với ngày hiệu lực = effDate; trả version_id. */
    private String registerForSite0(String docId, String effDate) {
        createdDocs.add(docId);
        String site0 = DbSupport.seed().siteIds.get(0);
        return DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId),
                ItServices.versionInput(docId, DbSupport.seed().authorId, effDate),
                List.of(site0)));
    }

    private String receiptIdForSite(String versionId, String siteId) {
        return svc().receipts.listReceiptsForVersion(versionId).stream()
                .filter(r -> r.siteId().equals(siteId)).findFirst().orElseThrow().receiptId();
    }

    /** UAT-30: DC Site chấp nhận → chap_nhan + apply_date = ngày site (hôm nay). */
    @Test
    void dcSiteChapNhanApplyDateBangNgaySite() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String versionId = registerForSite0("UAT-RCPT-" + System.currentTimeMillis(), today);
        String receiptId = receiptIdForSite(versionId, site0);

        String dcCookie = login(dcEmailForSite(site0));
        ResponseEntity<String> resp = postForm("/site-receipts/" + receiptId + "/accept", dcCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.FOUND, resp.getStatusCode(), "accept thành công → redirect");

        var db = DbSupport.plainJdbc();
        assertEquals("chap_nhan", db.queryForObject(
                "SELECT status FROM document_receipt WHERE receipt_id = ?::uuid", String.class, receiptId));
        assertEquals(today, db.queryForObject(
                "SELECT apply_date::text FROM document_receipt WHERE receipt_id = ?::uuid",
                String.class, receiptId));
    }

    /** UAT-31 [BẢO MẬT]: chấp nhận trước ngày hiệu lực site → receipt.before_effective (4xx). */
    @Test
    void chapNhanTruocNgaySiteBiChan() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String future = Clock.isoTodayVN();
        // ngày hiệu lực ở tương lai
        future = java.time.LocalDate.parse(future).plusDays(5).toString();
        String versionId = registerForSite0("UAT-RCPT-FUT-" + System.currentTimeMillis(), future);
        String receiptId = receiptIdForSite(versionId, site0);

        String dcCookie = login(dcEmailForSite(site0));
        ResponseEntity<String> resp = postForm("/site-receipts/" + receiptId + "/accept", dcCookie,
                new LinkedMultiValueMap<>());
        // Non-htmx: BusinessException bubble → trang error giữ status (422 mặc định).
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(),
                "chấp nhận trước ngày hiệu lực site phải bị chặn");
        assertEquals("cho_phan_hoi", DbSupport.plainJdbc().queryForObject(
                "SELECT status FROM document_receipt WHERE receipt_id = ?::uuid", String.class, receiptId));
    }

    /** UAT-32/80 [BẢO MẬT]: DC Site site khác mở phiếu → 403. */
    @Test
    void dcSiteKhacMoPhieuBi403() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String versionId = registerForSite0("UAT-RCPT-OSITE-" + System.currentTimeMillis(), today);
        String receiptId = receiptIdForSite(versionId, site0);

        // DC Site của site KHÁC site0.
        String otherSite = seed.siteIds.get(1);
        String otherDcCookie = login(dcEmailForSite(otherSite));
        ResponseEntity<String> resp = get("/site-receipts/" + receiptId, otherDcCookie);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    /** UAT-33: từ chối sai_dang_ky → tu_choi + hiện ở trang chủ Author. */
    @Test
    void tuChoiSaiDangKyHienOHomeAuthor() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String docId = "UAT-RCPT-REJ-" + System.currentTimeMillis();
        String versionId = registerForSite0(docId, today);
        String receiptId = receiptIdForSite(versionId, site0);

        String dcCookie = login(dcEmailForSite(site0));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("reject_type", "sai_dang_ky");
        form.add("note", "Sai số hiệu");
        ResponseEntity<String> resp = postForm("/site-receipts/" + receiptId + "/reject", dcCookie, form);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());

        assertEquals("tu_choi", DbSupport.plainJdbc().queryForObject(
                "SELECT status FROM document_receipt WHERE receipt_id = ?::uuid", String.class, receiptId));

        // Trang chủ Author hiển thị bản bị từ chối.
        String authorCookie = login(AUTHOR_EMAIL);
        ResponseEntity<String> home = get("/", authorCookie);
        assertEquals(HttpStatus.OK, home.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(home.getBody() != null && home.getBody().contains(docId),
                "trang chủ Author phải liệt kê tài liệu bị từ chối " + docId);
    }

    /** UAT-34 [BẢO MẬT]: reject loại không hợp lệ → 400. */
    @Test
    void tuChoiLoaiKhongHopLe400() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String versionId = registerForSite0("UAT-RCPT-BAD-" + System.currentTimeMillis(), today);
        String receiptId = receiptIdForSite(versionId, site0);

        String dcCookie = login(dcEmailForSite(site0));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("reject_type", "loai-sai");
        form.add("note", "x");
        ResponseEntity<String> resp = postForm("/site-receipts/" + receiptId + "/reject", dcCookie, form);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    /** Email DC Site đúng site (dựa site_code của seed). */
    private String dcEmailForSite(String siteId) {
        String code = DbSupport.plainJdbc().queryForObject(
                "SELECT site_code FROM org_site WHERE site_id = ?::uuid", String.class, siteId);
        return "MML".equals(code) ? DC_MML_EMAIL : DC_HCM_EMAIL;
    }
}
