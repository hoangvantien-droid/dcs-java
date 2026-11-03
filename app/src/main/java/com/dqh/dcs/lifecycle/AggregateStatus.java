package com.dqh.dcs.lifecycle;

/**
 * lifecycle/AggregateStatus — trạng thái TỔNG HỢP ở mức phiên bản, suy từ tập
 * receipt (port src/domain/lifecycle-status.ts AggregateStatus).
 *
 *   chua_phat_hanh : chưa có receipt nào.
 *   dang_cho       : có receipt nhưng chưa site nào phản hồi.
 *   mot_phan       : đã có ≥1 site chấp nhận, còn site khác chờ.
 *   tranh_chap     : có ≥1 site từ chối (cần Author xử lý).
 *   toan_bo        : mọi site đã chấp nhận.
 */
public enum AggregateStatus {
    chua_phat_hanh,
    dang_cho,
    mot_phan,
    tranh_chap,
    toan_bo;
}
