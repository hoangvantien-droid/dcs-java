package com.dqh.dcs.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * ClockTest — khẳng định todayVN() quy đổi đúng biên nửa đêm giờ VN (UTC+7).
 * Không cần DB.
 */
class ClockTest {

    @Test
    void beforeMidnightVN_staysSameDay() {
        // 16:30Z + 7h = 23:30 VN cùng ngày 2026-01-01.
        Instant now = Instant.parse("2026-01-01T16:30:00Z");
        assertThat(Clock.todayVN(now)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(Clock.isoTodayVN(now)).isEqualTo("2026-01-01");
    }

    @Test
    void atMidnightVN_rollsToNextDay() {
        // 17:00Z + 7h = 00:00 VN ⇒ đã sang ngày 2026-01-02.
        Instant now = Instant.parse("2026-01-01T17:00:00Z");
        assertThat(Clock.todayVN(now)).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(Clock.isoTodayVN(now)).isEqualTo("2026-01-02");
    }

    @Test
    void todayVN_usesVietnamZone() {
        assertThat(Clock.ZONE_VN.getId()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(Clock.todayVN()).isNotNull();
        assertThat(Clock.isoTodayVN()).matches("\\d{4}-\\d{2}-\\d{2}");
    }
}
