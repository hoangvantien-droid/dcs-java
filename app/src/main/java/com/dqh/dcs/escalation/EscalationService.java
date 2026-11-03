package com.dqh.dcs.escalation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * escalation/EscalationService — leo thang tồn đọng (port src/domain/escalation.ts,
 * T-082). Wrapper gọi THẲNG fn_run_escalations (QĐ-A) — mỗi bậc gửi ĐÚNG MỘT
 * LẦN (ràng buộc uq_escalation). Chỉ thông báo, không đổi trạng thái tài liệu.
 */
@Service
public class EscalationService {

    private final NamedParameterJdbcTemplate jdbc;

    public EscalationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chạy leo thang. Trả về số thông báo đã gửi. */
    public int runEscalations() {
        Integer n = jdbc.queryForObject(
                "SELECT fn_run_escalations() AS n", new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }
}
