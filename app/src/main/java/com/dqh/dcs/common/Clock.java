package com.dqh.dcs.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * common/Clock — đồng hồ "ngày hôm nay" theo múi giờ Việt Nam (port src/lib/clock.ts).
 *
 * today() cũ dùng giờ UTC, lệch một ngày với giờ VN vào buổi tối (UTC+7). Mọi so sánh
 * "ngày hiệu lực" / "ngày áp dụng" phải theo lịch VN, nên dùng todayVN() thay cho today().
 *
 * Tham số now (Instant) cho phép test biên nửa đêm giờ VN mà không giả lập hệ thống.
 */
public final class Clock {

    /** Múi giờ nghiệp vụ cố định: Việt Nam (UTC+7). */
    public static final ZoneId ZONE_VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private Clock() {
    }

    /** Ngày hôm nay theo lịch Việt Nam. */
    public static LocalDate todayVN() {
        return todayVN(Instant.now());
    }

    /** Ngày (theo lịch Việt Nam) của thời điểm {@code now} — dùng cho test biên. */
    public static LocalDate todayVN(Instant now) {
        return LocalDate.ofInstant(now, ZONE_VN);
    }

    /** Ngày hôm nay theo lịch Việt Nam dạng chuỗi 'YYYY-MM-DD'. */
    public static String isoTodayVN() {
        return isoTodayVN(Instant.now());
    }

    /** Ngày (theo lịch Việt Nam) của thời điểm {@code now} dạng chuỗi 'YYYY-MM-DD'. */
    public static String isoTodayVN(Instant now) {
        return todayVN(now).format(ISO_DATE);
    }
}
