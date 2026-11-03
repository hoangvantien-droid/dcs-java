package com.dqh.dcs.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * AggregateStatusTest — suy trạng thái tổng hợp từ số liệu receipt (thuần).
 * Tái hiện các nhánh của aggregateStatusOf trong src/domain/lifecycle-status.ts.
 */
class AggregateStatusTest {

    @Test
    void khongCoReceiptLaChuaPhatHanh() {
        assertEquals(AggregateStatus.chua_phat_hanh,
                LifecycleStatus.aggregateStatusOf(new ReceiptTally(0, 0, 0, 0)));
    }

    @Test
    void coTuChoiLaTranhChapDuDaChapNhanMotPhan() {
        // Ưu tiên tranh_chap khi có bất kỳ từ chối nào.
        assertEquals(AggregateStatus.tranh_chap,
                LifecycleStatus.aggregateStatusOf(new ReceiptTally(3, 1, 1, 1)));
    }

    @Test
    void taatCaChapNhanLaToanBo() {
        assertEquals(AggregateStatus.toan_bo,
                LifecycleStatus.aggregateStatusOf(new ReceiptTally(2, 2, 0, 0)));
    }

    @Test
    void coMotSoChapNhanConLaiChoLaMotPhan() {
        assertEquals(AggregateStatus.mot_phan,
                LifecycleStatus.aggregateStatusOf(new ReceiptTally(3, 1, 0, 2)));
    }

    @Test
    void coReceiptNhungChuaAiPhanHoiLaDangCho() {
        assertEquals(AggregateStatus.dang_cho,
                LifecycleStatus.aggregateStatusOf(new ReceiptTally(2, 0, 0, 2)));
    }
}
