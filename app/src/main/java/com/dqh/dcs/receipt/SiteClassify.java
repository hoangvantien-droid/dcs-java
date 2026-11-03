package com.dqh.dcs.receipt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * receipt/SiteClassify — phân loại site cho thao tác 'phát hành thêm / phát hành
 * lại' (port classifySitesForRepublish trong src/domain/receipt.ts, Task 1).
 *
 * THUẦN: không chạm DB, không async — để unit-test trực tiếp.
 *   - invalid: site requested KHÔNG thuộc candidate (ngoài scope).
 *   - blockedByContentReject: có BẤT KỲ receipt tu_choi + sai_noi_dung (toàn version).
 *   - newOnes: hợp lệ + chưa có receipt.
 *   - republish: hợp lệ + receipt tu_choi loại sai_dang_ky.
 *   - skipped: hợp lệ nhưng chap_nhan / cho_phan_hoi / tu_choi+sai_noi_dung.
 */
public final class SiteClassify {

    private SiteClassify() {
    }

    /** Một receipt hiện có (chỉ các trường cần cho phân loại). */
    public record ExistingReceipt(String siteId, String status, String responseType) {
    }

    public record Input(
            List<String> candidateSiteIds,
            List<ExistingReceipt> existingReceipts,
            List<String> requestedSiteIds) {
    }

    public record Result(
            List<String> newOnes,
            List<String> republish,
            List<String> skipped,
            List<String> invalid,
            boolean blockedByContentReject) {
    }

    public static Result classifySitesForRepublish(Input input) {
        Set<String> candidate = new HashSet<>(input.candidateSiteIds());
        Map<String, ExistingReceipt> bySite = new HashMap<>();
        for (ExistingReceipt r : input.existingReceipts()) {
            bySite.put(r.siteId(), r);
        }
        boolean blockedByContentReject = input.existingReceipts().stream()
                .anyMatch(r -> "tu_choi".equals(r.status()) && "sai_noi_dung".equals(r.responseType()));

        List<String> newOnes = new ArrayList<>();
        List<String> republish = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String siteId : input.requestedSiteIds()) {
            if (seen.contains(siteId)) {
                continue;
            }
            seen.add(siteId);
            if (!candidate.contains(siteId)) {
                invalid.add(siteId);
                continue;
            }
            ExistingReceipt rc = bySite.get(siteId);
            if (rc == null) {
                newOnes.add(siteId);
                continue;
            }
            if ("tu_choi".equals(rc.status()) && "sai_dang_ky".equals(rc.responseType())) {
                republish.add(siteId);
                continue;
            }
            skipped.add(siteId);
        }
        return new Result(newOnes, republish, skipped, invalid, blockedByContentReject);
    }
}
