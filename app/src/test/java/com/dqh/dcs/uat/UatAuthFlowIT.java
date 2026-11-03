package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.dqh.dcs.common.Clock;
import com.dqh.dcs.support.DbSupport;

/**
 * UatAuthFlowIT — UAT-01..07 (đăng nhập, phiên, phân quyền 4 vai, Admin-thuần
 * 403 ở /documents) + UAT-10/11 (Author đăng ký ban hành qua multipart; Reader
 * mở /register bị 403). Chạy qua HTTP THẬT. Tự-SKIP khi không có DB (xem
 * UatHttpBase).
 */
class UatAuthFlowIT extends UatHttpBase {

    private final java.util.List<String> createdDocs = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String d : createdDocs) {
            DbSupport.cleanupDoc(d);
        }
    }

    /** UAT-01: đăng nhập đúng → cookie dcs_sid + vào được trang chủ. */
    @Test
    void dangNhapDungDatCookieVaVaoTrangChu() {
        String cookie = login(AUTHOR_EMAIL);
        assertTrue(cookie.startsWith("dcs_sid="));
        ResponseEntity<String> home = get("/", cookie);
        assertEquals(HttpStatus.OK, home.getStatusCode());
    }

    /** UAT-02 [BẢO MẬT]: đăng nhập sai → 401, không cấp cookie. */
    @Test
    void dangNhapSaiKhongCapCookie() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", AUTHOR_EMAIL);
        form.add("password", "sai-mat-khau");
        ResponseEntity<String> resp = postForm("/login", null, form);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertNull(extractSessionCookie(resp.getHeaders()));
    }

    /** UAT-03 [BẢO MẬT]: chưa đăng nhập mở /documents → 302 về /login. */
    @Test
    void chuaDangNhapVaoDocumentsBiChuyenLogin() {
        ResponseEntity<String> resp = get("/documents", null);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertTrue(location(resp).endsWith("/login"), "phải chuyển hướng /login");
    }

    /** UAT-04 [BẢO MẬT]: Admin thuần mở /documents → 403 (ngoài luồng tài liệu). */
    @Test
    void adminThuanVaoDocumentsBi403() {
        String cookie = login(ADMIN_EMAIL);
        ResponseEntity<String> resp = get("/documents", cookie);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    /** UAT-05: vai nghiệp vụ (Author/Reader/DC Site) mở /documents → 200. */
    @Test
    void vaiNghiepVuVaoDocumentsOk() {
        for (String email : new String[] { AUTHOR_EMAIL, READER_EMAIL, DC_MML_EMAIL }) {
            String cookie = login(email);
            ResponseEntity<String> resp = get("/documents", cookie);
            assertEquals(HttpStatus.OK, resp.getStatusCode(), "/documents cho " + email);
        }
    }

    /** UAT-06: đăng xuất huỷ phiên; cookie cũ không dùng lại được. */
    @Test
    void dangXuatHuyPhien() {
        String cookie = login(AUTHOR_EMAIL);
        ResponseEntity<String> out = get("/logout", cookie);
        assertEquals(HttpStatus.FOUND, out.getStatusCode());
        assertTrue(location(out).endsWith("/login"));
        // Dùng lại cookie cũ → phiên đã bị huỷ → coi như vô danh → /documents 302.
        ResponseEntity<String> after = get("/documents", cookie);
        assertEquals(HttpStatus.FOUND, after.getStatusCode());
        assertTrue(location(after).endsWith("/login"));
    }

    /** UAT-07 [BẢO MẬT]: cookie chữ ký sai → coi như vô danh → 302 /login. */
    @Test
    void cookieChuKySaiCoiNhuVoDanh() {
        ResponseEntity<String> resp = get("/documents", "dcs_sid=rac-khong-hop-le");
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());
        assertTrue(location(resp).endsWith("/login"));
    }

    /** UAT-10: Author đăng ký ban hành qua multipart → tạo bản + receipt cho site. */
    @Test
    void authorDangKyBanHanhTaoReceipt() {
        String cookie = login(AUTHOR_EMAIL);
        String site0 = DbSupport.seed().siteIds.get(0);
        String docId = "UAT-REG-" + System.currentTimeMillis();
        createdDocs.add(docId);
        String today = Clock.isoTodayVN();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("doc_id", docId);
        form.add("version_no", "1.0");
        form.add("title", "Tài liệu UAT " + docId);
        form.add("doc_type", "SOP");
        form.add("origin", "noi_bo");
        form.add("scope_level", "group");
        form.add("approval_record_no", "PD-UAT-001");
        form.add("issued_date", today);
        form.add("effective_date", today);
        form.add("review_cycle_months", "12");
        form.add("site_ids", site0);
        ByteArrayResource pdf = new ByteArrayResource("%PDF-1.4 UAT".getBytes()) {
            @Override
            public String getFilename() {
                return docId + ".pdf";
            }
        };
        form.add("file", pdf);

        HttpHeaders headers = cookie(cookie);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> resp = noRedirect().postForEntity(
                "http://localhost:" + port + "/register",
                new HttpEntity<>(form, headers), String.class);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode(), "đăng ký thành công → redirect /my-documents");
        assertTrue(location(resp).endsWith("/my-documents"));

        // Kiểm DB: bản da_ban_hanh + 1 receipt cho site0.
        var db = DbSupport.plainJdbc();
        String status = db.queryForObject(
                "SELECT status FROM document_versions WHERE doc_id = ? ORDER BY version_no DESC LIMIT 1",
                String.class, docId);
        assertEquals("da_ban_hanh", status);
        Integer rc = db.queryForObject(
                "SELECT count(*)::int FROM document_receipt r JOIN document_versions v "
                + "ON v.version_id = r.version_id WHERE v.doc_id = ? AND r.site_id = ?::uuid",
                Integer.class, docId, site0);
        assertEquals(1, rc);
    }

    /** UAT-11 [BẢO MẬT]: Reader mở /register → 403. */
    @Test
    void readerMoRegisterBi403() {
        String cookie = login(READER_EMAIL);
        ResponseEntity<String> resp = get("/register", cookie);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    private static String location(ResponseEntity<String> resp) {
        assertNotNull(resp.getHeaders().getLocation(), "thiếu header Location");
        return resp.getHeaders().getLocation().toString();
    }
}
