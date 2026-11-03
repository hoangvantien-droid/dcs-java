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
 * UatReaderAckIT — Reader đọc/xác nhận đọc + báo sai nội dung sau chấp nhận.
 *   UAT-40: Reader thuộc ma trận phân phối → POST acknowledge ghi nhận đã đọc.
 *   UAT-41 [BẢO MẬT]: Reader ngoài diện → acknowledge bị chặn (ack.not_required, 422).
 *   UAT-42 (IND-04): Reader báo sai nội dung sau khi site đã chấp nhận →
 *     receipt GIỮ chap_nhan, report sang bao_sai_noi_dung_sau_chap_nhan; Author
 *     recall được dù 100% chấp nhận (bước recall kiểm luôn UAT-51).
 */
class UatReaderAckIT extends UatHttpBase {

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

    private String readerDeptId() {
        return DbSupport.plainJdbc().queryForObject(
                "SELECT primary_dept_id::text FROM users WHERE email = 'reader@mml.io'", String.class);
    }

    private String readerSiteId() {
        return DbSupport.plainJdbc().queryForObject(
                "SELECT dp.site_id::text FROM users u JOIN org_department dp ON dp.dept_id = u.primary_dept_id "
                + "WHERE u.email = 'reader@mml.io'", String.class);
    }

    private String dcEmailForSite(String siteId) {
        String code = DbSupport.plainJdbc().queryForObject(
                "SELECT site_code FROM org_site WHERE site_id = ?::uuid", String.class, siteId);
        return "MML".equals(code) ? DC_MML_EMAIL : DC_HCM_EMAIL;
    }

    /** Đăng ký bản group hiệu lực hôm nay cho site của Reader. */
    private String registerEffectiveForReaderSite(String docId) {
        createdDocs.add(docId);
        String readerSite = readerSiteId();
        return DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId),
                ItServices.versionInput(docId, DbSupport.seed().authorId, Clock.isoTodayVN()),
                List.of(readerSite)));
    }

    /** UAT-40: Reader thuộc ma trận → acknowledge ghi nhận đã đọc. */
    @Test
    void readerXacNhanDaDoc() {
        var seed = DbSupport.seed();
        String docId = "UAT-ACK-" + System.currentTimeMillis();
        String versionId = registerEffectiveForReaderSite(docId);
        String readerDept = readerDeptId();

        // Ma trận phân phối: Dept chính của Reader phải đọc.
        DbSupport.tx().execute(st -> DbSupport.plainJdbc().update(
                "INSERT INTO distribution_matrix (doc_id, department_id, functional_group_id, require_ack, created_by) "
                + "VALUES (?, ?::uuid, NULL, TRUE, ?::uuid) ON CONFLICT DO NOTHING",
                docId, readerDept, seed.authorId));

        String readerCookie = login(READER_EMAIL);
        ResponseEntity<String> resp = postForm("/documents/" + versionId + "/acknowledge", readerCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.FOUND, resp.getStatusCode(), "acknowledge thành công → redirect");

        String readerId = DbSupport.plainJdbc().queryForObject(
                "SELECT user_id::text FROM users WHERE email = 'reader@mml.io'", String.class);
        Integer n = DbSupport.plainJdbc().queryForObject(
                "SELECT count(*)::int FROM read_acknowledgements WHERE version_id = ?::uuid AND user_id = ?::uuid",
                Integer.class, versionId, readerId);
        assertEquals(1, n);
    }

    /** UAT-41 [BẢO MẬT]: Reader ngoài diện → acknowledge bị chặn (422). */
    @Test
    void readerNgoaiDienKhongXacNhanDuoc() {
        String docId = "UAT-ACK-OUT-" + System.currentTimeMillis();
        String versionId = registerEffectiveForReaderSite(docId);
        // KHÔNG tạo distribution_matrix → Reader ngoài diện.

        String readerCookie = login(READER_EMAIL);
        ResponseEntity<String> resp = postForm("/documents/" + versionId + "/acknowledge", readerCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
    }

    /** UAT-42 (IND-04) + UAT-51: báo sai nội dung sau chấp nhận; Author recall được. */
    @Test
    void baoSaiNoiDungSauChapNhanIND04() {
        var seed = DbSupport.seed();
        String readerSite = readerSiteId();
        String docId = "UAT-IND04-" + System.currentTimeMillis();
        String versionId = registerEffectiveForReaderSite(docId);
        String receiptId = svc().receipts.listReceiptsForVersion(versionId).stream()
                .filter(r -> r.siteId().equals(readerSite)).findFirst().orElseThrow().receiptId();

        // DC Site của site Reader chấp nhận phiếu (qua HTTP).
        String dcCookie = login(dcEmailForSite(readerSite));
        ResponseEntity<String> acc = postForm("/site-receipts/" + receiptId + "/accept", dcCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.FOUND, acc.getStatusCode());

        // Reader báo sai nội dung (qua HTTP).
        String readerCookie = login(READER_EMAIL);
        MultiValueMap<String, String> rep = new LinkedMultiValueMap<>();
        rep.add("note", "Nội dung sai sau khi áp dụng");
        ResponseEntity<String> report = postForm("/documents/" + versionId + "/report", readerCookie, rep);
        assertEquals(HttpStatus.FOUND, report.getStatusCode(), "report thành công → redirect");

        String reportId = DbSupport.plainJdbc().queryForObject(
                "SELECT report_id::text FROM document_issue_report WHERE version_id = ?::uuid "
                + "ORDER BY created_at DESC LIMIT 1", String.class, versionId);

        // DC Site chuyển report thành từ chối sai_noi_dung (qua HTTP).
        MultiValueMap<String, String> conv = new LinkedMultiValueMap<>();
        conv.add("reject_type", "sai_noi_dung");
        conv.add("note", "Sai thực tế");
        ResponseEntity<String> convResp = postForm("/issue-reports/" + reportId + "/convert", dcCookie, conv);
        assertEquals(HttpStatus.FOUND, convResp.getStatusCode());

        var db = DbSupport.plainJdbc();
        // Receipt GIỮ chap_nhan.
        assertEquals("chap_nhan", db.queryForObject(
                "SELECT status FROM document_receipt WHERE receipt_id = ?::uuid", String.class, receiptId));
        // Report sang trạng thái post-accept.
        assertEquals("bao_sai_noi_dung_sau_chap_nhan", db.queryForObject(
                "SELECT status FROM document_issue_report WHERE report_id = ?::uuid", String.class, reportId));

        // UAT-51: Author recall được dù 100% site đã chấp nhận (qua HTTP).
        String authorCookie = login(AUTHOR_EMAIL);
        MultiValueMap<String, String> recall = new LinkedMultiValueMap<>();
        recall.add("reason", "Thu hồi do sai nội dung");
        ResponseEntity<String> recallResp = postForm("/my-documents/" + versionId + "/recall",
                authorCookie, recall);
        assertEquals(HttpStatus.FOUND, recallResp.getStatusCode(), "recall thành công → redirect");
        assertEquals("thu_hoi", db.queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, versionId));
    }
}
