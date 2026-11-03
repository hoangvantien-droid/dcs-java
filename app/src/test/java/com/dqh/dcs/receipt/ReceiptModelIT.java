package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.lifecycle.AggregateStatus;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * ReceiptModelIT — tích hợp mô hình receipt (port
 * test/domain/receipt-model.integration.test.ts). Tự SKIP khi không có
 * Docker/DB (Assumptions). Đăng ký → da_ban_hanh + receipt cho_phan_hoi/site;
 * chấp nhận/từ chối đổi tổng hợp; phải chọn ≥1 site; DC Site lạ site bị chặn.
 */
class ReceiptModelIT {

    private ItServices svc;
    private final List<String> created = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        svc = new ItServices();
    }

    @AfterEach
    void tearDown() {
        if (DbSupport.dbOk()) {
            for (String d : created) {
                DbSupport.cleanupDoc(d);
            }
        }
    }

    private String register(String docId, List<String> siteIds) {
        created.add(docId);
        return DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, DbSupport.seed().authorId, null),
                siteIds));
    }

    @Test
    void dangKySinhReceiptChoPhanHoiMoiSite() {
        var seed = DbSupport.seed();
        String docId = "IT-RCPT-" + System.currentTimeMillis();
        String versionId = register(docId, seed.siteIds);

        String status = DbSupport.plainJdbc().queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, versionId);
        assertEquals("da_ban_hanh", status);

        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        assertEquals(seed.siteIds.size(), receipts.size());
        assertTrue(receipts.stream().allMatch(r -> "cho_phan_hoi".equals(r.status())));
        assertEquals(AggregateStatus.dang_cho, svc.receipts.aggregateStatus(versionId));
    }

    @Test
    void phaiChonItNhatMotSite() {
        String docId = "IT-RCPT-NOSITE-" + System.currentTimeMillis();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> register(docId, List.of()));
        assertEquals("reg.no_site", ex.getCode());
    }

    @Test
    void dcSiteChapNhanDoiTongHopMotPhan() {
        var seed = DbSupport.seed();
        String docId = "IT-RCPT-ACC-" + System.currentTimeMillis();
        String versionId = register(docId, seed.siteIds);
        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        var mine = receipts.stream().filter(r -> r.siteId().equals(seed.siteIds.get(0))).findFirst().orElseThrow();

        DbSupport.tx().execute(s -> {
            svc.receipts.acceptReceipt(mine.receiptId(), seed.dcSite0UserId);
            return null;
        });
        var after = svc.receipts.listReceiptsForVersion(versionId);
        var acc = after.stream().filter(r -> r.receiptId().equals(mine.receiptId())).findFirst().orElseThrow();
        assertEquals("chap_nhan", acc.status());
        assertTrue(acc.applyDate() != null);
        assertEquals(AggregateStatus.mot_phan, svc.receipts.aggregateStatus(versionId));
    }

    @Test
    void dcSiteLaSiteBiChan403() {
        var seed = DbSupport.seed();
        String docId = "IT-RCPT-CROSS-" + System.currentTimeMillis();
        // Chỉ phát hành tới site[0]; DC của site[1] không có quyền phản hồi.
        String versionId = register(docId, List.of(seed.siteIds.get(0)));
        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> {
                    svc.receipts.acceptReceipt(receipts.get(0).receiptId(), seed.dcSite1UserId);
                    return null;
                }));
        assertEquals("receipt.no_permission", ex.getCode());
    }
}
