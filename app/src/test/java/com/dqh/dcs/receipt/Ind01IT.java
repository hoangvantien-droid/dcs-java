package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.registration.VersionInput;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * Ind01IT — IND-01: phiếu của bản het_hieu_luc bị loại (port
 * test/domain/ind01-superseded-receipt.integration.test.ts). Ban hành v2.0 thay
 * thế v1.0 → v1.0 sang het_hieu_luc; phiếu v1 biến mất khỏi hàng chờ + chấp
 * nhận bị chặn (receipt.superseded). Tự SKIP khi không có DB.
 */
class Ind01IT {

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

    @Test
    void banThayTheLoaiPhieuCuKhoiHangChoVaChanChapNhan() {
        var seed = DbSupport.seed();
        String site = seed.siteIds.get(0);
        String docId = "IT-IND01-" + System.currentTimeMillis();
        created.add(docId);
        String today = com.dqh.dcs.common.Clock.isoTodayVN();

        String v1 = DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId),
                new VersionInput(docId, "1.0", "LC", today, null, today, 12, null, "thu_cong",
                        "/tmp/a.pdf", "hash-ind01-1", seed.authorId, "", null, null, null),
                List.of(site)));
        var rc1 = svc.receipts.listReceiptsForVersion(v1);
        String receipt1 = rc1.stream().filter(r -> r.siteId().equals(site)).findFirst().orElseThrow().receiptId();

        var before = svc.receipts.pendingReceiptsForSites(List.of(site));
        assertTrue(before.stream().anyMatch(r -> r.receiptId().equals(receipt1)));

        DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId),
                new VersionInput(docId, "2.0", "LC v2", today, null, today, 12, null, "thu_cong",
                        "/tmp/b.pdf", "hash-ind01-2", seed.authorId, "", v1, "nho", null),
                List.of(site)));

        assertEquals("het_hieu_luc", DbSupport.plainJdbc().queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, v1));

        var afterQueue = svc.receipts.pendingReceiptsForSites(List.of(site));
        assertFalse(afterQueue.stream().anyMatch(r -> r.receiptId().equals(receipt1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> {
                    svc.receipts.acceptReceipt(receipt1, seed.dcSite0UserId);
                    return null;
                }));
        assertEquals("receipt.superseded", ex.getCode());
    }
}
