package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * LockedAcceptedIT — ĐIỂM 6/7: khóa thống nhất sau khi chấp nhận (port
 * test/domain/accepted-lock.integration.test.ts). Sau khi ≥1 site chấp nhận,
 * editVersionMetadata VÀ publishAdditionalSites đều bị khóa cùng mã
 * 'issue.locked_accepted'. Tự SKIP khi không có DB.
 */
class LockedAcceptedIT {

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
    void sauKhiChapNhanEditVaRepublishDeuBiKhoaCungMa() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String docId = "IT-LOCK-" + System.currentTimeMillis();
        created.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, null),
                List.of(site0)));
        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        var mine = receipts.stream().filter(r -> r.siteId().equals(site0)).findFirst().orElseThrow();
        DbSupport.tx().execute(s -> {
            svc.receipts.acceptReceipt(mine.receiptId(), seed.dcSite0UserId);
            return null;
        });

        BusinessException edit = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> {
                    svc.receipts.editVersionMetadata(versionId, seed.authorId,
                            new MetadataPatch("Sửa sau khi chấp nhận", null, null, null, null, null));
                    return null;
                }));
        assertEquals("issue.locked_accepted", edit.getCode());

        String otherSite = seed.siteIds.size() > 1 ? seed.siteIds.get(1) : site0;
        BusinessException pub = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> svc.receipts.publishAdditionalSites(
                        versionId, seed.authorId, List.of(otherSite), Map.of())));
        assertEquals("issue.locked_accepted", pub.getCode());
    }
}
