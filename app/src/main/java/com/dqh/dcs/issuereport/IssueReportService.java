package com.dqh.dcs.issuereport;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.audit.AuditEntry;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.audit.EventType;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.permissions.PermissionService;

/**
 * issuereport/IssueReportService — report bất thường của Reader + thẩm định DC
 * Site (port src/domain/issue-report.ts).
 *
 * Reader tạo report (moi) gắn version + site → DC Site cùng site thẩm định:
 * chuyển thành từ chối receipt (chuyen_phan_hoi) hoặc bác bỏ (bac_bo).
 */
@Service
public class IssueReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final PermissionService permissions;

    public IssueReportService(NamedParameterJdbcTemplate jdbc, AuditService audit,
            PermissionService permissions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.permissions = permissions;
    }

    public record IssueReportRow(String reportId, String versionId, String siteId, String siteCode,
            String docId, String versionNo, String title, String reportedBy, String reporterName,
            String note, String status, String createdAt) {
    }

    /**
     * Reader gửi report bất thường. site_id lấy từ Dept chính của người báo.
     */
    @Transactional
    public String createIssueReport(String versionId, String userId, String note) {
        if (note == null || note.trim().isEmpty()) {
            throw new BusinessException("report.note", "Vui lòng mô tả bất thường");
        }
        List<String> siteRows = jdbc.queryForList(
                "SELECT dp.site_id FROM users u LEFT JOIN org_department dp ON dp.dept_id = u.primary_dept_id "
                + "WHERE u.user_id = CAST(:u AS uuid)",
                new MapSqlParameterSource("u", userId), String.class);
        String siteId = siteRows.isEmpty() ? null : siteRows.get(0);
        if (siteId == null) {
            throw new BusinessException("report.no_site",
                    "Tài khoản chưa gắn site (Dept chính) — không gửi report được");
        }
        List<String> ver = jdbc.queryForList(
                "SELECT version_id FROM document_versions WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", versionId), String.class);
        if (ver.isEmpty()) {
            throw new BusinessException("report.not_found", "Không tìm thấy tài liệu");
        }
        String trimmed = note.trim();
        String reportId = jdbc.queryForObject(
                "INSERT INTO document_issue_report (version_id, site_id, reported_by, note, status) "
                + "VALUES (CAST(:v AS uuid), CAST(:site AS uuid), CAST(:u AS uuid), :note, 'moi') "
                + "RETURNING report_id",
                new MapSqlParameterSource().addValue("v", versionId).addValue("site", siteId)
                        .addValue("u", userId).addValue("note", trimmed), String.class);
        audit.writeAudit(AuditEntry.of(EventType.bao_bat_thuong, "document_issue_report", reportId)
                .actorUserId(userId).afterState(Map.of("version_id", versionId, "site_id", siteId)));
        return reportId;
    }

    /** Hộp report chờ thẩm định của các site DC Site phụ trách. */
    public List<IssueReportRow> pendingReportsForSites(List<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT ir.report_id, ir.version_id, ir.site_id, s.site_code, "
                + "v.doc_id, v.version_no, v.title, ir.reported_by, u.full_name AS reporter_name, "
                + "ir.note, ir.status, ir.created_at::text AS created_at "
                + "FROM document_issue_report ir "
                + "JOIN org_site s ON s.site_id = ir.site_id "
                + "JOIN document_versions v ON v.version_id = ir.version_id "
                + "LEFT JOIN users u ON u.user_id = ir.reported_by "
                + "WHERE ir.status = 'moi' AND ir.site_id = ANY(CAST(:ids AS uuid[])) "
                + "ORDER BY ir.created_at DESC",
                new MapSqlParameterSource("ids", "{" + String.join(",", siteIds) + "}"),
                (rs, i) -> new IssueReportRow(rs.getString("report_id"), rs.getString("version_id"),
                        rs.getString("site_id"), rs.getString("site_code"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("reported_by"),
                        rs.getString("reporter_name"), rs.getString("note"), rs.getString("status"),
                        rs.getString("created_at")));
    }

    private record ReportRow(String reportId, String versionId, String siteId, String status) {
    }

    private ReportRow loadReportForReview(String reportId) {
        List<ReportRow> rows = jdbc.query(
                "SELECT report_id, version_id, site_id, status FROM document_issue_report "
                + "WHERE report_id = CAST(:id AS uuid) FOR UPDATE",
                new MapSqlParameterSource("id", reportId),
                (rs, i) -> new ReportRow(rs.getString("report_id"), rs.getString("version_id"),
                        rs.getString("site_id"), rs.getString("status")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertSiteControl(String userId, String siteId) {
        Set<String> siteIds = permissions.siteIdsWithDocControl(userId);
        if (!siteIds.contains(siteId)) {
            throw new BusinessException("report.no_permission",
                    "Không đủ thẩm quyền thẩm định report của site này", 403);
        }
    }

    /**
     * DC Site chuyển report thành TỪ CHỐI receipt của chính site đó. Điểm 7:
     * chap_nhan + sai_dang_ky → CHẶN; chap_nhan + sai_noi_dung → báo sai nội
     * dung sau chấp nhận (giữ chap_nhan).
     */
    @Transactional
    public void convertReportToRejection(String reportId, String userId, String rejectType, String note) {
        ReportRow r = loadReportForReview(reportId);
        if (r == null) {
            throw new BusinessException("report.not_found", "Không tìm thấy report");
        }
        if (!"moi".equals(r.status())) {
            throw new BusinessException("report.bad_status", "Report đã được thẩm định");
        }
        assertSiteControl(userId, r.siteId());

        List<String[]> receiptRows = jdbc.query(
                "SELECT receipt_id, status FROM document_receipt "
                + "WHERE version_id = CAST(:v AS uuid) AND site_id = CAST(:s AS uuid)",
                new MapSqlParameterSource().addValue("v", r.versionId()).addValue("s", r.siteId()),
                (rs, i) -> new String[] { rs.getString("receipt_id"), rs.getString("status") });
        if (receiptRows.isEmpty()) {
            throw new BusinessException("report.no_receipt",
                    "Site chưa nhận tài liệu này — không chuyển thành từ chối được");
        }
        String receiptId = receiptRows.get(0)[0];
        String receiptStatus = receiptRows.get(0)[1];
        String trimmedNote = (note == null || note.trim().isEmpty())
                ? "Chuyển từ report bất thường của người đọc" : note.trim();

        if ("chap_nhan".equals(receiptStatus)) {
            if ("sai_dang_ky".equals(rejectType)) {
                throw new BusinessException("report.already_accepted",
                        "Site đã chấp nhận tài liệu — không thể chuyển report thành từ chối sai đăng ký; "
                        + "phải thu hồi và ban hành phiên bản mới");
            }
            // sai_noi_dung sau khi đã chấp nhận: giữ receipt ở chap_nhan.
            jdbc.update(
                    "UPDATE document_issue_report SET status = 'bao_sai_noi_dung_sau_chap_nhan', "
                    + "reviewed_by = CAST(:u AS uuid), reviewed_at = now(), review_note = :note "
                    + "WHERE report_id = CAST(:id AS uuid)",
                    new MapSqlParameterSource().addValue("u", userId).addValue("note", trimmedNote)
                            .addValue("id", reportId));
            audit.writeAudit(AuditEntry.of(EventType.bao_sai_noi_dung_sau_chap_nhan,
                            "document_issue_report", reportId)
                    .actorUserId(userId)
                    .afterState(Map.of("receipt_id", receiptId, "site_id", r.siteId(),
                            "note", trimmedNote, "receipt_status", "chap_nhan")));
            return;
        }

        if (!"cho_phan_hoi".equals(receiptStatus)) {
            throw new BusinessException("report.receipt_not_pending",
                    "Receipt của site không còn ở trạng thái chờ phản hồi — không chuyển thành từ chối được");
        }

        String convertNote = (note == null || note.trim().isEmpty()) ? null : note.trim();
        jdbc.update(
                "UPDATE document_issue_report SET status = 'chuyen_phan_hoi', reviewed_by = CAST(:u AS uuid), "
                + "reviewed_at = now(), review_note = :note WHERE report_id = CAST(:id AS uuid)",
                new MapSqlParameterSource().addValue("u", userId).addValue("note", convertNote)
                        .addValue("id", reportId));
        audit.writeAudit(AuditEntry.of(EventType.chuyen_report_phan_hoi, "document_issue_report", reportId)
                .actorUserId(userId).afterState(Map.of("receipt_id", receiptId, "reject_type", rejectType)));
        jdbc.update(
                "UPDATE document_receipt SET status = 'tu_choi', response_type = :rt, response_note = :note, "
                + "apply_date = NULL, responded_by = CAST(:u AS uuid), responded_at = now() "
                + "WHERE receipt_id = CAST(:rid AS uuid) AND status = 'cho_phan_hoi'",
                new MapSqlParameterSource().addValue("rt", rejectType).addValue("note", trimmedNote)
                        .addValue("u", userId).addValue("rid", receiptId));
        audit.writeAudit(AuditEntry.of(EventType.tu_choi_nhan, "document_receipt", receiptId)
                .actorUserId(userId)
                .afterState(Map.of("site_id", r.siteId(), "response_type", rejectType,
                        "note", trimmedNote, "from_report", reportId)));
    }

    /** DC Site bác bỏ report (kèm lý do). Không tác động receipt/tài liệu. */
    @Transactional
    public void dismissReport(String reportId, String userId, String reviewNote) {
        if (reviewNote == null || reviewNote.trim().isEmpty()) {
            throw new BusinessException("report.dismiss_note", "Bác bỏ report bắt buộc kèm lý do");
        }
        ReportRow r = loadReportForReview(reportId);
        if (r == null) {
            throw new BusinessException("report.not_found", "Không tìm thấy report");
        }
        if (!"moi".equals(r.status())) {
            throw new BusinessException("report.bad_status", "Report đã được thẩm định");
        }
        assertSiteControl(userId, r.siteId());
        String trimmed = reviewNote.trim();
        jdbc.update(
                "UPDATE document_issue_report SET status = 'bac_bo', reviewed_by = CAST(:u AS uuid), "
                + "reviewed_at = now(), review_note = :note WHERE report_id = CAST(:id AS uuid)",
                new MapSqlParameterSource().addValue("u", userId).addValue("note", trimmed)
                        .addValue("id", reportId));
        audit.writeAudit(AuditEntry.of(EventType.bac_bo_report, "document_issue_report", reportId)
                .actorUserId(userId).afterState(Map.of("review_note", trimmed)));
    }
}
