package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dqh.dcs.receipt.SiteClassify.ExistingReceipt;
import com.dqh.dcs.receipt.SiteClassify.Input;
import com.dqh.dcs.receipt.SiteClassify.Result;

/**
 * SiteClassifyTest — phân loại site cho 'phát hành thêm / phát hành lại' (thuần).
 * Tái hiện classifySitesForRepublish trong src/domain/receipt.ts.
 */
class SiteClassifyTest {

    @Test
    void phanLoaiNewRepublishSkippedInvalid() {
        Result r = SiteClassify.classifySitesForRepublish(new Input(
                List.of("s1", "s2", "s3", "s4"),
                List.of(
                        new ExistingReceipt("s2", "tu_choi", "sai_dang_ky"),   // republish
                        new ExistingReceipt("s3", "chap_nhan", null)),         // skipped
                List.of("s1", "s2", "s3", "s9")));                             // s9 invalid
        assertEquals(List.of("s1"), r.newOnes());
        assertEquals(List.of("s2"), r.republish());
        assertEquals(List.of("s3"), r.skipped());
        assertEquals(List.of("s9"), r.invalid());
        assertFalse(r.blockedByContentReject());
    }

    @Test
    void saiNoiDungLamBlockDuKhongYeuCauSiteDo() {
        Result r = SiteClassify.classifySitesForRepublish(new Input(
                List.of("s1", "s2"),
                List.of(new ExistingReceipt("s2", "tu_choi", "sai_noi_dung")),
                List.of("s1")));
        assertTrue(r.blockedByContentReject());
    }

    @Test
    void loaiTrungLapTrongRequested() {
        Result r = SiteClassify.classifySitesForRepublish(new Input(
                List.of("s1"),
                List.of(),
                List.of("s1", "s1", "s1")));
        assertEquals(List.of("s1"), r.newOnes());
    }

    @Test
    void tuChoiSaiNoiDungLaSkippedChoChinhSiteDo() {
        Result r = SiteClassify.classifySitesForRepublish(new Input(
                List.of("s1"),
                List.of(new ExistingReceipt("s1", "tu_choi", "sai_noi_dung")),
                List.of("s1")));
        assertEquals(List.of("s1"), r.skipped());
        assertTrue(r.republish().isEmpty());
    }
}
