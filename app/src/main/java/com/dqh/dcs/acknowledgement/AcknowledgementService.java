package com.dqh.dcs.acknowledgement;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.audit.AuditEntry;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.audit.EventType;
import com.dqh.dcs.common.BusinessException;

/**
 * acknowledgement/AcknowledgementService — xác nhận đọc (port
 * src/domain/acknowledgement.ts, T-069..071).
 *
 * Xác nhận đọc gắn với PHIÊN BẢN. Lưu bối cảnh (bộ phận, nhóm) TẠI THỜI ĐIỂM
 * xác nhận, không tra lại L1 sau (DCS-3 §F.2).
 */
@Service
public class AcknowledgementService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public AcknowledgementService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record AckState(boolean acknowledged, boolean inherited) {
    }

    /** Người dùng có thuộc diện phải đọc phiên bản này không (qua phép giao). */
    public boolean mustAcknowledge(String versionId, String userId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM v_distribution_resolved dr "
                + "JOIN document_versions v ON v.doc_id = dr.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid) AND dr.user_id = CAST(:u AS uuid) AND dr.require_ack) AS ok",
                new MapSqlParameterSource().addValue("v", versionId).addValue("u", userId), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    public AckState hasAcknowledged(String versionId, String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT inherited_from_version_id FROM read_acknowledgements "
                + "WHERE version_id = CAST(:v AS uuid) AND user_id = CAST(:u AS uuid)",
                new MapSqlParameterSource().addValue("v", versionId).addValue("u", userId), String.class);
        if (rows.isEmpty()) {
            return new AckState(false, false);
        }
        return new AckState(true, rows.get(0) != null);
    }

    /** Ghi nhận đã đọc — lưu bối cảnh tại thời điểm xác nhận. */
    @Transactional
    public void acknowledge(String versionId, String userId) {
        if (!mustAcknowledge(versionId, userId)) {
            throw new BusinessException("ack.not_required",
                    "Bạn không thuộc diện phải xác nhận đọc tài liệu này");
        }
        List<String[]> ctxRows = jdbc.query(
                "SELECT dr.department_id, dr.functional_group_id FROM v_distribution_resolved dr "
                + "JOIN document_versions v ON v.doc_id = dr.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid) AND dr.user_id = CAST(:u AS uuid) LIMIT 1",
                new MapSqlParameterSource().addValue("v", versionId).addValue("u", userId),
                (rs, i) -> new String[] { rs.getString("department_id"), rs.getString("functional_group_id") });
        String deptId = ctxRows.isEmpty() ? null : ctxRows.get(0)[0];
        String fgId = ctxRows.isEmpty() ? null : ctxRows.get(0)[1];
        jdbc.update(
                "INSERT INTO read_acknowledgements (version_id, user_id, department_id, functional_group_id) "
                + "VALUES (CAST(:v AS uuid), CAST(:u AS uuid), CAST(:dept AS uuid), CAST(:fg AS uuid)) "
                + "ON CONFLICT (version_id, user_id) DO NOTHING",
                new MapSqlParameterSource().addValue("v", versionId).addValue("u", userId)
                        .addValue("dept", deptId).addValue("fg", fgId));
        audit.writeAudit(AuditEntry.of(EventType.xac_nhan_doc, "document_versions", versionId)
                .actorUserId(userId));
    }
}
