package com.dqh.dcs.lifecycle;

import java.time.Instant;
import java.util.List;

import com.dqh.dcs.common.Clock;

/**
 * lifecycle/LifecycleStatus — NGUỒN DUY NHẤT về trạng thái vòng đời tài liệu và
 * khái niệm "hiện hành" (port src/domain/lifecycle-status.ts, điểm 9).
 *
 * Mô hình mới: da_ban_hanh là trạng thái "hiện hành" chính; mã cũ hieu_luc vẫn
 * coi là hiện hành trong giai đoạn chuyển đổi. IND-05: BỎ 'da_dang_ky' khỏi tập
 * hiện hành. Các module khác KHÔNG tự liệt kê mã trạng thái — gọi helper ở đây.
 */
public final class LifecycleStatus {

    /**
     * Tập trạng thái được coi là "hiện hành" — Reader được thấy, và là tài liệu
     * đang có hiệu lực để site nhận xử lý. Mã mới trước, mã cũ (tương thích) sau.
     * IND-05: KHÔNG gồm 'da_dang_ky'.
     */
    public static final List<String> CURRENT_VIEW_STATUSES = List.of("da_ban_hanh", "hieu_luc");

    private LifecycleStatus() {
    }

    /** Trạng thái này có được coi là tài liệu hiện hành không. */
    public static boolean isCurrentlyEffective(String status) {
        if (status == null) {
            return false;
        }
        return CURRENT_VIEW_STATUSES.contains(status);
    }

    /**
     * Trạng thái version KHÓA phản hồi/hàng chờ của receipt (IND-01): phiếu của
     * phiên bản đã THU HỒI hoặc đã HẾT HIỆU LỰC không còn được chấp nhận và phải
     * biến mất khỏi hàng chờ/badge. Helper THUẦN dùng chung giữa truy vấn hàng
     * chờ và kiểm quyền để hai nơi không lệch nhau.
     */
    public static boolean isReceiptBlockingVersionStatus(String status) {
        return "thu_hoi".equals(status) || "het_hieu_luc".equals(status);
    }

    /**
     * Tài liệu đã THỰC SỰ có hiệu lực TẠI HÔM NAY: trạng thái hiện hành VÀ ngày
     * hiệu lực đã tới (điểm 4). effectiveDate dạng 'YYYY-MM-DD' so chuỗi được.
     */
    public static boolean isEffectiveNow(String status, String effectiveDate) {
        return isEffectiveNow(status, effectiveDate, Instant.now());
    }

    /** Biến thể nhận Instant để test biên nửa đêm giờ VN. */
    public static boolean isEffectiveNow(String status, String effectiveDate, Instant now) {
        if (!isCurrentlyEffective(status)) {
            return false;
        }
        if (effectiveDate == null || effectiveDate.isEmpty()) {
            return false;
        }
        String today = Clock.isoTodayVN(now);
        return slice10(effectiveDate).compareTo(today) <= 0;
    }

    /**
     * Suy trạng thái tổng hợp từ số liệu receipt (port aggregateStatusOf). Ưu
     * tiên "tranh_chap" khi có bất kỳ từ chối nào — tín hiệu cần Author xử lý,
     * quan trọng hơn việc một phần site đã chấp nhận.
     */
    public static AggregateStatus aggregateStatusOf(ReceiptTally t) {
        if (t.total() == 0) {
            return AggregateStatus.chua_phat_hanh;
        }
        if (t.rejected() > 0) {
            return AggregateStatus.tranh_chap;
        }
        if (t.accepted() == t.total()) {
            return AggregateStatus.toan_bo;
        }
        if (t.accepted() > 0) {
            return AggregateStatus.mot_phan;
        }
        return AggregateStatus.dang_cho;
    }

    public static String slice10(String s) {
        return s.length() > 10 ? s.substring(0, 10) : s;
    }
}
