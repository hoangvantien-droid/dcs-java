package com.dqh.dcs.lifecycle;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * lifecycle/LifecycleService — vòng đời tài liệu theo lịch (port src/domain/lifecycle.ts,
 * T-035). Wrapper gọi THẲNG hàm PL/pgSQL (QĐ-A) — KHÔNG re-implement logic.
 */
@Service
public class LifecycleService {

    private final NamedParameterJdbcTemplate jdbc;

    public LifecycleService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chạy chuyển hiệu lực theo ngày. Trả về số phiên bản đã kích hoạt. */
    public int activateDueVersions() {
        Integer n = jdbc.queryForObject(
                "SELECT fn_activate_due_versions() AS n", new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }

    /** Sinh bản ghi rà soát cho tài liệu tới hạn. Trả về số bản ghi sinh mới. */
    public int generateDueReviews() {
        Integer n = jdbc.queryForObject(
                "SELECT fn_generate_due_reviews() AS n", new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }
}
