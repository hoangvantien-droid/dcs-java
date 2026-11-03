package com.dqh.dcs.distribution;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * distribution/DistributionService — ma trận phân phối, giải người nhận PHÉP
 * GIAO (port src/domain/distribution.ts, T-065, T-066).
 *
 * Giải người nhận = PHÉP GIAO (v_distribution_resolved). KHÔNG đổi thành phép
 * hợp (DCS-9 bẫy #5).
 */
@Service
public class DistributionService {

    private final NamedParameterJdbcTemplate jdbc;

    public DistributionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Giải danh sách người nhận cho một tài liệu — PHÉP GIAO qua view. */
    public List<String> resolveRecipients(String docId) {
        return jdbc.queryForList(
                "SELECT DISTINCT user_id FROM v_distribution_resolved WHERE doc_id = :d",
                new MapSqlParameterSource("d", docId), String.class);
    }

    /** Đếm số người sẽ nhận nếu thêm một dòng ma trận (bộ phận [+ nhóm]) — phép giao. */
    public int countRecipientsFor(String departmentId, String functionalGroupId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(DISTINCT ud.user_id)::int AS n FROM user_departments ud "
                + "WHERE ud.dept_id = CAST(:dept AS uuid) AND ud.is_active "
                + "AND (CAST(:fg AS uuid) IS NULL OR EXISTS (SELECT 1 FROM functional_group_members fm "
                + "  WHERE fm.group_id = CAST(:fg AS uuid) AND fm.user_id = ud.user_id))",
                new MapSqlParameterSource().addValue("dept", departmentId).addValue("fg", functionalGroupId),
                Integer.class);
        return n == null ? 0 : n;
    }
}
