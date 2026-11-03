package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
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
 * UatPerSiteEffectiveIT — ngày hiệu lực theo từng site qua HTTP THẬT.
 *   UAT-20: Author POST /my-documents/{id}/publish-sites thêm site B kèm
 *     eff_<B> khác ngày site A → thêm phiếu site B với site_effective_date riêng.
 *   UAT-21 [BẢO MẬT]: bản chưa tới ngày hiệu lực → Reader xem /documents/{id}
 *     bị 404 (cổng read-only effective_date <= CURRENT_DATE); khi hiệu lực thì
 *     xem được. Phần apply_date độc lập theo site do PerSiteEffectiveIT phủ ở
 *     tầng domain.
 */
class UatPerSiteEffectiveIT extends UatHttpBase {

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

    private static String shiftDays(int d) {
        return LocalDate.parse(Clock.isoTodayVN()).plusDays(d).toString();
    }

    /** UAT-20: thêm site B với ngày hiệu lực riêng qua HTTP publish-sites. */
    @Test
    void themSiteBVoiNgayHieuLucRieng() {
        var seed = DbSupport.seed();
        String siteA = seed.siteIds.get(0);
        String siteB = seed.siteIds.get(1);
        String docId = "UAT-PSITE-" + System.currentTimeMillis();
        createdDocs.add(docId);
        String effGoc = shiftDays(10);
        String versionId = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, effGoc),
                List.of(siteA)));

        String siteBEff = shiftDays(3); // khác ngày site A (gốc +10)
        String authorCookie = login(AUTHOR_EMAIL);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("site_ids", siteB);
        form.add("eff_" + siteB, siteBEff);
        ResponseEntity<String> resp = postForm("/my-documents/" + versionId + "/publish-sites",
                authorCookie, form);
        assertEquals(HttpStatus.FOUND, resp.getStatusCode(), "publish-sites thành công → redirect issuance");

        var db = DbSupport.plainJdbc();
        // Site B có phiếu + ngày hiệu lực RIÊNG (khác ngày gốc +10).
        assertEquals(siteBEff, db.queryForObject(
                "SELECT site_effective_date::text FROM document_receipt "
                + "WHERE version_id = ?::uuid AND site_id = ?::uuid", String.class, versionId, siteB));
        // Site A đăng ký ban đầu: site_effective_date NULL (dùng effective_date gốc của bản).
        org.junit.jupiter.api.Assertions.assertNull(db.queryForObject(
                "SELECT site_effective_date::text FROM document_receipt "
                + "WHERE version_id = ?::uuid AND site_id = ?::uuid", String.class, versionId, siteA),
                "site A giữ site_effective_date NULL; ngày hiệu lực lấy từ effective_date gốc");
        // Hai site có ngày hiệu lực áp dụng KHÁC nhau (gốc +10 vs +3).
        org.junit.jupiter.api.Assertions.assertEquals(effGoc, db.queryForObject(
                "SELECT effective_date::text FROM document_versions WHERE version_id = ?::uuid",
                String.class, versionId));
    }

    /** UAT-21 [BẢO MẬT]: Reader không thấy bản chưa tới ngày hiệu lực; thấy khi đã hiệu lực. */
    @Test
    void readerKhongThayTruocNgayHieuLuc() {
        var seed = DbSupport.seed();
        String siteA = seed.siteIds.get(0);

        // (1) Bản hiệu lực tương lai → Reader xem chi tiết bị 404.
        String futureDoc = "UAT-PSITE-FUT-" + System.currentTimeMillis();
        createdDocs.add(futureDoc);
        String futureVid = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(futureDoc),
                ItServices.versionInput(futureDoc, seed.authorId, shiftDays(7)),
                List.of(siteA)));

        String readerCookie = login(READER_EMAIL);
        ResponseEntity<String> future = get("/documents/" + futureVid, readerCookie);
        assertEquals(HttpStatus.NOT_FOUND, future.getStatusCode(),
                "Reader không được thấy bản chưa tới ngày hiệu lực");

        // (2) Bản hiệu lực hôm nay → Reader xem được.
        String nowDoc = "UAT-PSITE-NOW-" + System.currentTimeMillis();
        createdDocs.add(nowDoc);
        String nowVid = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(nowDoc),
                ItServices.versionInput(nowDoc, seed.authorId, Clock.isoTodayVN()),
                List.of(siteA)));
        ResponseEntity<String> now = get("/documents/" + nowVid, readerCookie);
        assertEquals(HttpStatus.OK, now.getStatusCode(), "Reader thấy bản đã hiệu lực");
    }
}
