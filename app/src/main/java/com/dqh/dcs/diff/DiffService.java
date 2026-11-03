package com.dqh.dcs.diff;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.audit.AuditEntry;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.audit.EventType;

/**
 * diff/DiffService — so sánh phiên bản (port src/domain/diff.ts, T-059/T-060).
 *
 * Gọi fn_diff_versions + fn_diff_reliability (QĐ-A, KHÔNG re-implement). KHÔNG
 * lưu kết quả. Việc Admin đã xem ghi vết bằng sự kiện xem_so_sanh (mở khoá ô 9b).
 */
@Service
public class DiffService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public DiffService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Độ tin cậy của so sánh — quyết định có hiện cảnh báo không (T-060). */
    public String diffReliability(String fromVersionId, String toVersionId) {
        return jdbc.queryForObject(
                "SELECT fn_diff_reliability(CAST(:from AS uuid), CAST(:to AS uuid)) AS r",
                new MapSqlParameterSource().addValue("from", fromVersionId).addValue("to", toVersionId),
                String.class);
    }

    /** Danh sách khác biệt giữa hai phiên bản (mục thêm/xoá/đổi). */
    public List<DiffRow> diffVersions(String fromVersionId, String toVersionId) {
        return jdbc.query(
                "SELECT section_code, change_type, old_title, new_title, old_text, new_text "
                + "FROM fn_diff_versions(CAST(:from AS uuid), CAST(:to AS uuid)) ORDER BY section_code",
                new MapSqlParameterSource().addValue("from", fromVersionId).addValue("to", toVersionId),
                (rs, i) -> new DiffRow(rs.getString("section_code"), rs.getString("change_type"),
                        rs.getString("old_title"), rs.getString("new_title"),
                        rs.getString("old_text"), rs.getString("new_text")));
    }

    /** Ghi vết Admin đã mở so sánh (điều kiện mở ô tích 9b). */
    @Transactional
    public boolean recordViewedDiff(String userId, String fromVersionId, String toVersionId) {
        Boolean matches = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document_versions "
                + "WHERE version_id = CAST(:to AS uuid) AND supersedes_version_id = CAST(:from AS uuid)) AS ok",
                new MapSqlParameterSource().addValue("to", toVersionId).addValue("from", fromVersionId),
                Boolean.class);
        if (!Boolean.TRUE.equals(matches)) {
            return false;
        }
        audit.writeAudit(AuditEntry.of(EventType.xem_so_sanh, "document_versions", toVersionId)
                .actorUserId(userId));
        return true;
    }
}
