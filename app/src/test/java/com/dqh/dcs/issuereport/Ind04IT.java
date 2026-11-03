package com.dqh.dcs.issuereport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * Ind04IT — IND-04: báo sai nội dung sau chấp nhận (port
 * test/domain/ind04-content-error.integration.test.ts).
 *   - sai_noi_dung sau chap_nhan: receipt GIỮ chap_nhan, report sang
 *     'bao_sai_noi_dung_sau_chap_nhan' (IND-04a/b);
 *   - Author vẫn recall được dù 100% site chấp nhận (IND-04c);
 *   - sai_dang_ky sau chap_nhan: bị chặn 'report.already_accepted'.
 * Tự SKIP khi không có DB / seed thiếu Reader+DC cùng site.
 */
class Ind04IT {

    private ItServices svc;
    private String readerId;
    private String readerSite;
    private String dcId;
    private final List<String> created = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        svc = new ItServices();
        var db = DbSupport.plainJdbc();
        var row = db.queryForList(
                "SELECT u.user_id::text AS uid, dp.site_id::text AS sid "
                + "FROM users u JOIN org_department dp ON dp.dept_id = u.primary_dept_id "
                + "WHERE u.email = 'reader@mml.io' LIMIT 1");
        Assumptions.assumeTrue(!row.isEmpty(), "Chưa seed Reader có site");
        readerId = (String) row.get(0).get("uid");
        readerSite = (String) row.get(0).get("sid");
        var dc = db.queryForList(
                "SELECT user_id::text AS uid FROM user_roles WHERE role_code = 'DC_SITE_DOC_CONTROL' "
                + "AND is_active AND scope_site_id = ?::uuid LIMIT 1", readerSite);
        Assumptions.assumeTrue(!dc.isEmpty(), "Chưa seed DC Site cho site của Reader");
        dcId = (String) dc.get(0).get("uid");
    }

    @AfterEach
    void tearDown() {
        if (DbSupport.dbOk()) {
            for (String d : created) {
                DbSupport.cleanupDoc(d);
            }
        }
    }

    private String registerAndAccept(String docId) {
        created.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, DbSupport.seed().authorId, null),
                List.of(readerSite)));
        var mine = svc.receipts.listReceiptsForVersion(versionId).stream()
                .filter(r -> r.siteId().equals(readerSite)).findFirst().orElseThrow();
        DbSupport.tx().execute(s -> {
            svc.receipts.acceptReceipt(mine.receiptId(), dcId);
            return null;
        });
        return versionId;
    }

    @Test
    void saiNoiDungSauChapNhanGiuReceiptChapNhanVaChoRecall() {
        var seed = DbSupport.seed();
        String docId = "IT-IND04-" + System.currentTimeMillis();
        String versionId = registerAndAccept(docId);

        String reportId = DbSupport.tx().execute(s -> svc.issueReports.createIssueReport(
                versionId, readerId, "Nội dung sai sau khi áp dụng"));
        DbSupport.tx().execute(s -> {
            svc.issueReports.convertReportToRejection(reportId, dcId, "sai_noi_dung", "Sai thực tế");
            return null;
        });

        var db = DbSupport.plainJdbc();
        // receipt GIỮ chap_nhan.
        assertEquals("chap_nhan", db.queryForObject(
                "SELECT status FROM document_receipt WHERE version_id = ?::uuid AND site_id = ?::uuid",
                String.class, versionId, readerSite));
        // report sang trạng thái post-accept.
        assertEquals("bao_sai_noi_dung_sau_chap_nhan", db.queryForObject(
                "SELECT status FROM document_issue_report WHERE report_id = ?::uuid", String.class, reportId));
        // IND-04b: version xuất hiện trong truy vấn home của Author.
        Integer homeN = db.queryForObject(
                "SELECT count(*)::int FROM document_versions v JOIN document_issue_report ir "
                + "ON ir.version_id = v.version_id WHERE v.registrant_user_id = ?::uuid "
                + "AND ir.status = 'bao_sai_noi_dung_sau_chap_nhan' AND v.version_id = ?::uuid",
                Integer.class, seed.authorId, versionId);
        assertTrue(homeN != null && homeN > 0);

        // IND-04c: Author recall được dù 100% chấp nhận.
        DbSupport.tx().execute(s -> {
            svc.receipts.recallByAuthor(versionId, seed.authorId, "Thu hồi do sai nội dung");
            return null;
        });
        assertEquals("thu_hoi", db.queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, versionId));
    }

    @Test
    void saiDangKySauChapNhanBiChan() {
        String docId = "IT-IND04B-" + System.currentTimeMillis();
        String versionId = registerAndAccept(docId);
        String reportId = DbSupport.tx().execute(s -> svc.issueReports.createIssueReport(
                versionId, readerId, "Sai thông tin đăng ký"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> {
                    svc.issueReports.convertReportToRejection(reportId, dcId, "sai_dang_ky", "Sai đăng ký");
                    return null;
                }));
        assertEquals("report.already_accepted", ex.getCode());
    }
}
