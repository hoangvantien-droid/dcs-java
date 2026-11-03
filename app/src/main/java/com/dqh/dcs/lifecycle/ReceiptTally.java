package com.dqh.dcs.lifecycle;

/**
 * lifecycle/ReceiptTally — tóm tắt tập receipt để suy trạng thái tổng hợp
 * (port src/domain/lifecycle-status.ts ReceiptTally). Thuần, không chạm DB.
 */
public record ReceiptTally(int total, int accepted, int rejected, int pending) {
}
