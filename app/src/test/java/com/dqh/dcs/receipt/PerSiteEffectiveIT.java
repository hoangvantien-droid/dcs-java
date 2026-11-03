package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.common.Clock;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * PerSiteEffectiveIT — HƯỚNG 2: apply_date = site_effective_date độc lập (port
 * test/domain/apply-date.integration.test.ts). effective_date gốc ở tương lai;
 * site có ngày hiệu lực SỚM hơn gốc:
 *   (a) publishAdditionalSites OK;
 *   (b) chấp nhận TRƯỚC ngày site → chặn receipt.before_effective (giữ guard
 *       điểm 4), KHÔNG phải apply_before_effective (đã gỡ);
 *   (c) ngày site = hôm nay → chấp nhận OK, apply_date = ngày site.
 * Tự SKIP khi không có DB.
 */
class PerSiteEffectiveIT {

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

    private static String shiftDays(int days) {
        return LocalDate.parse(Clock.isoTodayVN()).plusDays(days).toString();
    }

    private String registerWithFutureEffective(String docId, String site0) {
        created.add(docId);
        String effGoc = shiftDays(10);
        return DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId),
                ItServices.versionInput(docId, DbSupport.seed().authorId, effGoc),
                List.of(site0)));
    }

    @Test
    void chapNhanTruocNgaySiteBiChanBeforeEffective() {
        var seed = DbSupport.seed();
        String siteB = seed.siteIds.get(1);
        String docId = "IT-APPLY-D2-" + System.currentTimeMillis();
        String versionId = registerWithFutureEffective(docId, seed.siteIds.get(0));

        String siteEff = shiftDays(2); // sớm hơn gốc (+10), nhưng tương lai so hôm nay
        var res = DbSupport.tx().execute(s -> svc.receipts.publishAdditionalSites(
                versionId, seed.authorId, List.of(siteB), Map.of(siteB, siteEff)));
        assertEquals(1, res.added());

        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        var rcB = receipts.stream().filter(r -> r.siteId().equals(siteB)).findFirst().orElseThrow();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DbSupport.tx().execute(s -> {
                    svc.receipts.acceptReceipt(rcB.receiptId(), seed.dcSite1UserId);
                    return null;
                }));
        assertEquals("receipt.before_effective", ex.getCode());
    }

    @Test
    void ngaySiteLaHomNayChapNhanOkApplyDateBangNgaySite() {
        var seed = DbSupport.seed();
        String siteB = seed.siteIds.get(1);
        String docId = "IT-APPLY-D2B-" + System.currentTimeMillis();
        String versionId = registerWithFutureEffective(docId, seed.siteIds.get(0));

        String siteEff = shiftDays(0); // = hôm nay: < gốc nhưng chấp nhận được ngay
        var res = DbSupport.tx().execute(s -> svc.receipts.publishAdditionalSites(
                versionId, seed.authorId, List.of(siteB), Map.of(siteB, siteEff)));
        assertEquals(1, res.added());

        var receipts = svc.receipts.listReceiptsForVersion(versionId);
        var rcB = receipts.stream().filter(r -> r.siteId().equals(siteB)).findFirst().orElseThrow();
        DbSupport.tx().execute(s -> {
            svc.receipts.acceptReceipt(rcB.receiptId(), seed.dcSite1UserId);
            return null;
        });
        var after = svc.receipts.listReceiptsForVersion(versionId);
        var acc = after.stream().filter(r -> r.receiptId().equals(rcB.receiptId())).findFirst().orElseThrow();
        assertEquals("chap_nhan", acc.status());
        assertEquals(siteEff, acc.applyDate());
    }
}
