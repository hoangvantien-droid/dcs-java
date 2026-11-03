package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

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
import com.dqh.dcs.support.ItServices;

/**
 * UatRecallIT — thu hồi + supersede.
 *   UAT-50 (IND-01): Author đăng ký v2.0 supersede v1.0 qua HTTP → v1 sang
 *     het_hieu_luc; phiếu v1 biến khỏi hàng chờ /site-receipts; accept phiếu v1
 *     bị chặn receipt.superseded (422).
 *   UAT-51: Author recall bản đã có site chấp nhận → bản sang thu_hoi (kiểm
 *     recall sau chấp nhận qua HTTP; trường hợp 100% + báo sai do UatReaderAckIT).
 */
class UatRecallIT extends UatHttpBase {

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

    private String dcEmailForSite(String siteId) {
        String code = DbSupport.plainJdbc().queryForObject(
                "SELECT site_code FROM org_site WHERE site_id = ?::uuid", String.class, siteId);
        return "MML".equals(code) ? DC_MML_EMAIL : DC_HCM_EMAIL;
    }

    private String receiptIdForSite(String versionId, String siteId) {
        return svc().receipts.listReceiptsForVersion(versionId).stream()
                .filter(r -> r.siteId().equals(siteId)).findFirst().orElseThrow().receiptId();
    }

    /** UAT-50 (IND-01): supersede qua HTTP loại phiếu cũ khỏi hàng chờ + chặn accept. */
    @Test
    void supersedeLoaiPhieuCuIND01() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String docId = "UAT-SUP-" + System.currentTimeMillis();
        createdDocs.add(docId);

        // v1.0 qua fixture (cùng DB).
        String v1 = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, today),
                List.of(site0)));
        String receipt1 = receiptIdForSite(v1, site0);

        // Phiếu v1 có trong hàng chờ của DC Site trước khi supersede.
        String dcCookie = login(dcEmailForSite(site0));
        ResponseEntity<String> queueBefore = get("/site-receipts", dcCookie);
        assertEquals(HttpStatus.OK, queueBefore.getStatusCode());

        // v2.0 supersede v1.0 qua HTTP /register (multipart, change_significance=nho).
        String authorCookie = login(AUTHOR_EMAIL);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("doc_id", docId);
        form.add("version_no", "2.0");
        form.add("title", "Tài liệu UAT v2 " + docId);
        form.add("doc_type", "SOP");
        form.add("origin", "noi_bo");
        form.add("scope_level", "group");
        form.add("issued_date", today);
        form.add("effective_date", today);
        form.add("review_cycle_months", "12");
        form.add("site_ids", site0);
        form.add("supersedes_version_id", v1);
        form.add("change_significance", "nho");
        ByteArrayResource pdf = new ByteArrayResource("%PDF-1.4 UAT v2".getBytes()) {
            @Override
            public String getFilename() {
                return docId + "-v2.pdf";
            }
        };
        form.add("file", pdf);
        HttpHeaders headers = cookie(authorCookie);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> reg = noRedirect().postForEntity(
                "http://localhost:" + port + "/register", new HttpEntity<>(form, headers), String.class);
        assertEquals(HttpStatus.FOUND, reg.getStatusCode(), "đăng ký v2 supersede → redirect");

        var db = DbSupport.plainJdbc();
        // v1 sang het_hieu_luc.
        assertEquals("het_hieu_luc", db.queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, v1));

        // Phiếu v1 biến khỏi hàng chờ (kiểm tầng domain cho chắc + accept bị chặn).
        var pending = svc().receipts.pendingReceiptsForSites(List.of(site0));
        assertFalse(pending.stream().anyMatch(r -> r.receiptId().equals(receipt1)),
                "phiếu v1 phải bị loại khỏi hàng chờ sau khi het_hieu_luc");

        // Accept phiếu v1 qua HTTP → chặn receipt.superseded (422).
        ResponseEntity<String> acc = postForm("/site-receipts/" + receipt1 + "/accept", dcCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, acc.getStatusCode(),
                "chấp nhận phiếu của bản đã het_hieu_luc phải bị chặn");
    }

    /** UAT-51: Author recall bản đã có site chấp nhận → thu_hoi (qua HTTP). */
    @Test
    void recallBanDaChapNhan() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String today = Clock.isoTodayVN();
        String docId = "UAT-RECALL-" + System.currentTimeMillis();
        createdDocs.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, today),
                List.of(site0)));
        String receiptId = receiptIdForSite(versionId, site0);

        // DC Site chấp nhận (qua HTTP).
        String dcCookie = login(dcEmailForSite(site0));
        ResponseEntity<String> acc = postForm("/site-receipts/" + receiptId + "/accept", dcCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.FOUND, acc.getStatusCode());

        // Author recall (qua HTTP).
        String authorCookie = login(AUTHOR_EMAIL);
        MultiValueMap<String, String> recall = new LinkedMultiValueMap<>();
        recall.add("reason", "Thu hồi dù đã chấp nhận");
        ResponseEntity<String> resp = postForm("/my-documents/" + versionId + "/recall", authorCookie, recall);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode());

        assertEquals("thu_hoi", DbSupport.plainJdbc().queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, versionId));
    }
}
