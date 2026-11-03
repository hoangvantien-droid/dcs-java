package com.dqh.dcs.receipt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import com.dqh.dcs.common.Clock;
import com.dqh.dcs.lifecycle.AggregateStatus;
import com.dqh.dcs.lifecycle.LifecycleStatus;
import com.dqh.dcs.lifecycle.ReceiptTally;
import com.dqh.dcs.permissions.PermissionService;

/**
 * receipt/ReceiptService — mô hình "bên nhận phản hồi" (receipt theo site)
 * (port src/domain/receipt.ts). Author đăng ký = BAN HÀNH THẲNG tới các site.
 * Mỗi site một mốc apply_date = ngày hiệu lực đã khai cho site đó. Kiểm quyền
 * đặt ở domain (DCS-8 §4.3). Ghi vết trong CÙNG giao dịch với thay đổi.
 */
@Service
public class ReceiptService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final PermissionService permissions;

    public ReceiptService(NamedParameterJdbcTemplate jdbc, AuditService audit, PermissionService permissions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.permissions = permissions;
    }

    public record SiteOption(String siteId, String siteCode, String name) {
    }

    /** Một dòng receipt theo site (ma trận phản hồi). Port ReceiptRow. */
    public record ReceiptRow(String receiptId, String versionId, String siteId, String siteCode,
            String siteName, String targetDeptId, String applyDate, String status, String responseType,
            String responseNote, String respondedBy, String respondedAt, String siteEffectiveDate,
            String effectiveDate) {
    }

    /** Receipt kèm thông tin phiên bản + cảnh báo site khác từ chối. Port PendingReceiptRow. */
    public record PendingReceiptRow(String receiptId, String versionId, String siteId, String siteCode,
            String siteName, String targetDeptId, String applyDate, String status, String responseType,
            String responseNote, String respondedBy, String respondedAt, String siteEffectiveDate,
            String effectiveDate, String docId, String versionNo, String title, String registrantUserId,
            String otherRejectType) {
    }

    /** Tài liệu đã thu hồi có liên quan tới site (nhắc DC Site). Port RecalledDocRow. */
    public record RecalledDocRow(String versionId, String docId, String versionNo, String title,
            String recallReason, String recalledAt) {
    }

    private static final String RECEIPT_ROW_COLS =
            "r.receipt_id, r.version_id, r.site_id, s.site_code, s.name AS site_name, "
            + "r.target_dept_id, r.apply_date::text AS apply_date, r.status, r.response_type, "
            + "r.response_note, r.responded_by, r.responded_at::text AS responded_at, "
            + "coalesce(r.site_effective_date, v.effective_date)::text AS site_effective_date, "
            + "v.effective_date::text AS effective_date";

    private static ReceiptRow mapReceiptRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ReceiptRow(rs.getString("receipt_id"), rs.getString("version_id"), rs.getString("site_id"),
                rs.getString("site_code"), rs.getString("site_name"), rs.getString("target_dept_id"),
                rs.getString("apply_date"), rs.getString("status"), rs.getString("response_type"),
                rs.getString("response_note"), rs.getString("responded_by"), rs.getString("responded_at"),
                rs.getString("site_effective_date"), rs.getString("effective_date"));
    }

    private static PendingReceiptRow mapPendingRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PendingReceiptRow(rs.getString("receipt_id"), rs.getString("version_id"),
                rs.getString("site_id"), rs.getString("site_code"), rs.getString("site_name"),
                rs.getString("target_dept_id"), rs.getString("apply_date"), rs.getString("status"),
                rs.getString("response_type"), rs.getString("response_note"), rs.getString("responded_by"),
                rs.getString("responded_at"), rs.getString("site_effective_date"),
                rs.getString("effective_date"), rs.getString("doc_id"), rs.getString("version_no"),
                rs.getString("title"), rs.getString("registrant_user_id"), rs.getString("other_reject_type"));
    }

    /** Liệt kê receipt của một version (ma trận phản hồi theo site). */
    public List<ReceiptRow> listReceiptsForVersion(String versionId) {
        return jdbc.query(
                "SELECT " + RECEIPT_ROW_COLS + " FROM document_receipt r "
                + "JOIN org_site s ON s.site_id = r.site_id "
                + "JOIN document_versions v ON v.version_id = r.version_id "
                + "WHERE r.version_id = :v ORDER BY s.site_code",
                new MapSqlParameterSource("v", versionId), ReceiptService::mapReceiptRow);
    }

    /** Hàng chờ phản hồi của một DC Site (cho_phan_hoi, loại thu_hoi/het_hieu_luc). */
    public List<PendingReceiptRow> pendingReceiptsForSites(List<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT " + RECEIPT_ROW_COLS + ", v.doc_id, v.version_no, v.title, v.registrant_user_id, "
                + "(SELECT r2.response_type FROM document_receipt r2 "
                + "  WHERE r2.version_id = r.version_id AND r2.status = 'tu_choi' "
                + "  ORDER BY CASE r2.response_type WHEN 'sai_noi_dung' THEN 0 ELSE 1 END LIMIT 1) AS other_reject_type "
                + "FROM document_receipt r "
                + "JOIN org_site s ON s.site_id = r.site_id "
                + "JOIN document_versions v ON v.version_id = r.version_id "
                + "WHERE r.status = 'cho_phan_hoi' AND v.status NOT IN ('thu_hoi', 'het_hieu_luc') "
                + "AND r.site_id = ANY(CAST(:ids AS uuid[])) "
                + "ORDER BY v.submitted_at DESC NULLS LAST, s.site_code",
                new MapSqlParameterSource("ids", toUuidArrayLiteral(siteIds)), ReceiptService::mapPendingRow);
    }

    /** Tài liệu ĐÃ THU HỒI có receipt tới các site người dùng phụ trách (nhắc, chỉ đọc). */
    public List<RecalledDocRow> recalledDocsForSites(List<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT DISTINCT v.version_id, v.doc_id, v.version_no, v.title, "
                + "v.recall_reason, v.updated_at::text AS recalled_at "
                + "FROM document_versions v JOIN document_receipt r ON r.version_id = v.version_id "
                + "WHERE v.status = 'thu_hoi' AND r.site_id = ANY(CAST(:ids AS uuid[])) "
                + "ORDER BY recalled_at DESC",
                new MapSqlParameterSource("ids", toUuidArrayLiteral(siteIds)),
                (rs, i) -> new RecalledDocRow(rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("recall_reason"),
                        rs.getString("recalled_at")));
    }

    /** Nạp một receipt để hiển thị màn phản hồi (kèm thông tin phiên bản). */
    public PendingReceiptRow loadReceiptForView(String receiptId) {
        List<PendingReceiptRow> rows = jdbc.query(
                "SELECT " + RECEIPT_ROW_COLS + ", v.doc_id, v.version_no, v.title, v.registrant_user_id, "
                + "(SELECT r2.response_type FROM document_receipt r2 "
                + "  WHERE r2.version_id = r.version_id AND r2.status = 'tu_choi' AND r2.receipt_id <> r.receipt_id "
                + "  ORDER BY CASE r2.response_type WHEN 'sai_noi_dung' THEN 0 ELSE 1 END LIMIT 1) AS other_reject_type "
                + "FROM document_receipt r "
                + "JOIN org_site s ON s.site_id = r.site_id "
                + "JOIN document_versions v ON v.version_id = r.version_id "
                + "WHERE r.receipt_id = :id",
                new MapSqlParameterSource("id", receiptId), ReceiptService::mapPendingRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Chuẩn hoá danh sách site_id gửi từ form (thuần): loại rỗng/khoảng trắng +
     * trùng lặp, giữ thứ tự xuất hiện (port normalizeSiteIds).
     */
    public static List<String> normalizeSiteIds(List<String> raw) {
        Set<String> seen = new LinkedHashSet<>();
        if (raw == null) {
            return new ArrayList<>();
        }
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isEmpty()) {
                seen.add(t);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Ngày hiệu lực THỰC của một site (thuần): ngày riêng khai cho site nếu có,
     * nếu không thì về ngày hiệu lực gốc của phiên bản (port effectiveForSite,
     * phương án 2a). Tách để unit-test không cần DB.
     */
    public static String effectiveForSite(String siteEff, String versionEff) {
        if (siteEff != null) {
            return siteEff;
        }
        return versionEff;
    }

    /** Giải tập SITE ứng viên theo phạm vi ban hành (để Author chọn tập site nhận). */
    public List<SiteOption> candidateSitesForScope(String scopeLevel, String scopeBuId,
            String scopeSiteId, String scopeDeptId) {
        switch (scopeLevel) {
            case "group":
                return jdbc.query("SELECT site_id, site_code, name FROM org_site ORDER BY site_code",
                        new MapSqlParameterSource(), ReceiptService::mapSiteOption);
            case "bu":
                return jdbc.query(
                        "SELECT s.site_id, s.site_code, s.name FROM org_site s "
                        + "JOIN org_site_bu sb ON sb.site_id = s.site_id "
                        + "WHERE sb.bu_id = :bu ORDER BY s.site_code",
                        new MapSqlParameterSource("bu", scopeBuId), ReceiptService::mapSiteOption);
            case "site":
                return jdbc.query(
                        "SELECT site_id, site_code, name FROM org_site WHERE site_id = :site",
                        new MapSqlParameterSource("site", scopeSiteId), ReceiptService::mapSiteOption);
            case "dept":
                return jdbc.query(
                        "SELECT s.site_id, s.site_code, s.name FROM org_site s "
                        + "JOIN org_department d ON d.site_id = s.site_id WHERE d.dept_id = :dept",
                        new MapSqlParameterSource("dept", scopeDeptId), ReceiptService::mapSiteOption);
            default:
                return List.of();
        }
    }

    private static SiteOption mapSiteOption(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SiteOption(rs.getString("site_id"), rs.getString("site_code"), rs.getString("name"));
    }

    /**
     * Phát hành một phiên bản tới tập site: tạo receipt cho_phan_hoi cho mỗi
     * site (idempotent theo version_id/site_id qua ON CONFLICT DO NOTHING). Phải
     * được gọi TRONG giao dịch của lời gọi cha. Trả về số receipt tạo mới.
     */
    public int publishToSites(String versionId, List<String> siteIds, String targetDeptId,
            String actorUserId, Map<String, String> siteEffectiveDates) {
        int created = 0;
        for (String siteId : siteIds) {
            String siteEff = siteEffectiveDates == null ? null : siteEffectiveDates.get(siteId);
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("version_id", versionId)
                    .addValue("site_id", siteId)
                    .addValue("target_dept_id", targetDeptId)
                    .addValue("site_effective_date", siteEff);
            List<String> rows = jdbc.queryForList(
                    "INSERT INTO document_receipt "
                    + "(version_id, site_id, target_dept_id, status, site_effective_date) "
                    + "VALUES (:version_id, :site_id, :target_dept_id, 'cho_phan_hoi', CAST(:site_effective_date AS date)) "
                    + "ON CONFLICT (version_id, site_id) DO NOTHING RETURNING receipt_id",
                    p, String.class);
            if (!rows.isEmpty()) {
                created += 1;
            }
        }
        if (created > 0) {
            audit.writeAudit(AuditEntry.of(EventType.ban_hanh, "document_versions", versionId)
                    .actorUserId(actorUserId)
                    .afterState(Map.of("site_count", created,
                            "target_dept_id", targetDeptId == null ? "" : targetDeptId)));
        }
        return created;
    }

    /** Đếm receipt theo trạng thái cho một version (để suy trạng thái tổng hợp). */
    public ReceiptTally receiptTally(String versionId) {
        return jdbc.queryForObject(
                "SELECT count(*)::int AS total, "
                + "count(*) FILTER (WHERE status = 'chap_nhan')::int AS accepted, "
                + "count(*) FILTER (WHERE status = 'tu_choi')::int AS rejected, "
                + "count(*) FILTER (WHERE status = 'cho_phan_hoi')::int AS pending "
                + "FROM document_receipt WHERE version_id = :v",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new ReceiptTally(rs.getInt("total"), rs.getInt("accepted"),
                        rs.getInt("rejected"), rs.getInt("pending")));
    }

    /** Trạng thái tổng hợp mức version, suy từ tập receipt. */
    public AggregateStatus aggregateStatus(String versionId) {
        return LifecycleStatus.aggregateStatusOf(receiptTally(versionId));
    }

    /** Số receipt đang chờ phản hồi của các site (badge sidebar). IND-01: loại thu_hoi/het_hieu_luc. */
    public int pendingReceiptCountForSites(List<String> siteIds) {
        if (siteIds.isEmpty()) {
            return 0;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*)::int AS n FROM document_receipt r "
                + "JOIN document_versions v ON v.version_id = r.version_id "
                + "WHERE r.status = 'cho_phan_hoi' "
                + "AND v.status NOT IN ('thu_hoi', 'het_hieu_luc') "
                + "AND r.site_id = ANY(CAST(:ids AS uuid[]))",
                new MapSqlParameterSource("ids", toUuidArrayLiteral(siteIds)), Integer.class);
        return n == null ? 0 : n;
    }

    /** "Đã có ≥1 site chấp nhận" — dùng để khóa sửa trực tiếp (Task 6). */
    public boolean hasAnyAcceptance(String versionId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document_receipt "
                + "WHERE version_id = :v AND status = 'chap_nhan') AS ok",
                new MapSqlParameterSource("v", versionId), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    // ========================================================================
    // PHẢN HỒI CỦA SITE (Task 4) — DC Site chấp nhận / từ chối receipt.
    // ========================================================================

    private record ReceiptContext(String receiptId, String versionId, String siteId, String status,
            String registrantUserId, String versionStatus, String effectiveDate, String siteEffectiveDate) {
    }

    private ReceiptContext loadReceiptForRespond(String receiptId) {
        List<ReceiptContext> rows = jdbc.query(
                "SELECT r.receipt_id, r.version_id, r.site_id, r.status, "
                + "v.registrant_user_id, v.status AS version_status, "
                + "v.effective_date::text AS effective_date, "
                + "r.site_effective_date::text AS site_effective_date "
                + "FROM document_receipt r JOIN document_versions v ON v.version_id = r.version_id "
                + "WHERE r.receipt_id = :id FOR UPDATE OF r",
                new MapSqlParameterSource("id", receiptId),
                (rs, i) -> new ReceiptContext(
                        rs.getString("receipt_id"), rs.getString("version_id"), rs.getString("site_id"),
                        rs.getString("status"), rs.getString("registrant_user_id"),
                        rs.getString("version_status"), rs.getString("effective_date"),
                        rs.getString("site_effective_date")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Kiểm quyền + phát hiện tự-phản-hồi; mode 'accept' thêm chặn điểm 4/5. */
    private boolean assertCanRespond(ReceiptContext rc, String userId, String mode) {
        if (!"cho_phan_hoi".equals(rc.status())) {
            throw new BusinessException("receipt.bad_status", "Receipt không còn ở trạng thái chờ phản hồi");
        }
        if (LifecycleStatus.isReceiptBlockingVersionStatus(rc.versionStatus())) {
            if ("thu_hoi".equals(rc.versionStatus())) {
                throw new BusinessException("receipt.recalled",
                        "Tài liệu đã bị thu hồi — không thể phản hồi phiếu này");
            }
            throw new BusinessException("receipt.superseded",
                    "Tài liệu đã bị phiên bản mới thay thế — không thể phản hồi phiếu này");
        }
        String effForSite = effectiveForSite(rc.siteEffectiveDate(), rc.effectiveDate());
        if ("accept".equals(mode) && effForSite != null
                && LifecycleStatus.slice10(effForSite).compareTo(Clock.isoTodayVN()) > 0) {
            throw new BusinessException("receipt.before_effective",
                    "Chưa tới ngày hiệu lực — không thể chấp nhận tài liệu này");
        }
        Set<String> siteIds = permissions.siteIdsWithDocControl(userId);
        if (!siteIds.contains(rc.siteId())) {
            throw new BusinessException("receipt.no_permission",
                    "Không đủ thẩm quyền phản hồi receipt của site này", 403);
        }
        return rc.registrantUserId() != null && rc.registrantUserId().equals(userId);
    }

    /** DC Site chấp nhận receipt: apply_date = ngày hiệu lực đã khai cho site. */
    @Transactional
    public void acceptReceipt(String receiptId, String userId) {
        ReceiptContext rc = loadReceiptForRespond(receiptId);
        if (rc == null) {
            throw new BusinessException("receipt.not_found", "Không tìm thấy receipt");
        }
        boolean selfResponded = assertCanRespond(rc, userId, "accept");
        String applyDate = effectiveForSite(rc.siteEffectiveDate(), rc.effectiveDate());
        if (applyDate == null) {
            throw new BusinessException("receipt.no_effective",
                    "Tài liệu chưa có ngày hiệu lực để chấp nhận");
        }
        jdbc.update(
                "UPDATE document_receipt SET status = 'chap_nhan', apply_date = CAST(:apply AS date), "
                + "response_type = NULL, response_note = NULL, "
                + "responded_by = :user, responded_at = now() WHERE receipt_id = :id",
                new MapSqlParameterSource().addValue("apply", applyDate)
                        .addValue("user", userId).addValue("id", receiptId));
        if (selfResponded) {
            audit.writeAudit(AuditEntry.of(EventType.tu_phan_hoi_canh_bao, "document_receipt", receiptId)
                    .actorUserId(userId)
                    .afterState(Map.of("action", "chap_nhan",
                            "canh_bao", "Người phản hồi là chính người đăng ký")));
        }
        audit.writeAudit(AuditEntry.of(EventType.chap_nhan_nhan, "document_receipt", receiptId)
                .actorUserId(userId)
                .afterState(Map.of("site_id", rc.siteId(), "apply_date", applyDate)));
    }

    /** DC Site từ chối receipt: bắt buộc loại + lý do. */
    @Transactional
    public void rejectReceipt(String receiptId, String userId, String rejectType, String note) {
        if (note == null || note.trim().isEmpty()) {
            throw new BusinessException("receipt.reason", "Từ chối bắt buộc kèm lý do sai sót");
        }
        ReceiptContext rc = loadReceiptForRespond(receiptId);
        if (rc == null) {
            throw new BusinessException("receipt.not_found", "Không tìm thấy receipt");
        }
        boolean selfResponded = assertCanRespond(rc, userId, "reject");
        String trimmed = note.trim();
        jdbc.update(
                "UPDATE document_receipt SET status = 'tu_choi', response_type = :rt, "
                + "response_note = :note, apply_date = NULL, "
                + "responded_by = :user, responded_at = now() WHERE receipt_id = :id",
                new MapSqlParameterSource().addValue("rt", rejectType).addValue("note", trimmed)
                        .addValue("user", userId).addValue("id", receiptId));
        if (selfResponded) {
            audit.writeAudit(AuditEntry.of(EventType.tu_phan_hoi_canh_bao, "document_receipt", receiptId)
                    .actorUserId(userId)
                    .afterState(Map.of("action", "tu_choi",
                            "canh_bao", "Người phản hồi là chính người đăng ký")));
        }
        audit.writeAudit(AuditEntry.of(EventType.tu_choi_nhan, "document_receipt", receiptId)
                .actorUserId(userId)
                .afterState(Map.of("site_id", rc.siteId(), "response_type", rejectType, "note", trimmed)));
    }

    // ========================================================================
    // AUTHOR SỬA / THU HỒI / XÓA (Task 6).
    // ========================================================================

    private record VersionOwnerRow(String versionId, String status, String registrantUserId) {
    }

    private VersionOwnerRow loadOwnedVersion(String versionId, String userId) {
        List<VersionOwnerRow> rows = jdbc.query(
                "SELECT version_id, status, registrant_user_id FROM document_versions "
                + "WHERE version_id = :v FOR UPDATE",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new VersionOwnerRow(rs.getString("version_id"), rs.getString("status"),
                        rs.getString("registrant_user_id")));
        VersionOwnerRow v = rows.isEmpty() ? null : rows.get(0);
        if (v == null) {
            throw new BusinessException("ver.not_found", "Không tìm thấy phiên bản");
        }
        if (!userId.equals(v.registrantUserId())) {
            throw new BusinessException("ver.not_owner",
                    "Chỉ người đăng ký được thao tác tài liệu này", 403);
        }
        return v;
    }

    private int acceptedCount(String versionId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*)::int AS n FROM document_receipt "
                + "WHERE version_id = :v AND status = 'chap_nhan'",
                new MapSqlParameterSource("v", versionId), Integer.class);
        return n == null ? 0 : n;
    }

    /** Khóa "đã có site chấp nhận" dùng chung cho sửa metadata và phát hành lại (điểm 6+7). */
    private void assertNotAcceptedLocked(String versionId) {
        if (acceptedCount(versionId) > 0) {
            throw new BusinessException("issue.locked_accepted",
                    "Đã có site chấp nhận/áp dụng — không sửa hoặc phát hành lại với metadata cũ, phải ra phiên bản mới");
        }
    }

    /** Sửa thông tin đăng ký tại chỗ (Loại 1 — sai_dang_ky). */
    @Transactional
    public void editVersionMetadata(String versionId, String userId, MetadataPatch patch) {
        VersionOwnerRow v = loadOwnedVersion(versionId, userId);
        if (!"da_ban_hanh".equals(v.status())) {
            throw new BusinessException("edit.bad_status",
                    "Chỉ sửa metadata khi tài liệu đang ở trạng thái đã ban hành");
        }
        assertNotAcceptedLocked(versionId);
        if (patch.effectiveDate() != null
                && LifecycleStatus.slice10(patch.effectiveDate()).compareTo(Clock.isoTodayVN()) < 0) {
            throw new BusinessException("edit.effective_past", "Không được đặt ngày hiệu lực về quá khứ");
        }
        boolean expiryClear = "".equals(patch.expiryDate());
        String expiryNew = (patch.expiryDate() != null && !patch.expiryDate().isEmpty())
                ? patch.expiryDate() : null;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("title", patch.title())
                .addValue("issued_date", patch.issuedDate())
                .addValue("effective_date", patch.effectiveDate())
                .addValue("review_cycle_months", patch.reviewCycleMonths())
                .addValue("expiry_clear", expiryClear)
                .addValue("expiry_new", expiryNew)
                .addValue("owner_department_id", patch.ownerDepartmentId())
                .addValue("v", versionId);
        jdbc.update(
                "UPDATE document_versions SET "
                + "title = coalesce(:title, title), "
                + "issued_date = coalesce(CAST(:issued_date AS date), issued_date), "
                + "effective_date = coalesce(CAST(:effective_date AS date), effective_date), "
                + "review_cycle_months = coalesce(:review_cycle_months, review_cycle_months), "
                + "expiry_date = CASE WHEN :expiry_clear THEN NULL "
                + "  ELSE coalesce(CAST(:expiry_new AS date), expiry_date) END, "
                + "owner_department_id = coalesce(CAST(:owner_department_id AS uuid), owner_department_id), "
                + "updated_at = now() WHERE version_id = :v",
                p);
        audit.writeAudit(AuditEntry.of(EventType.sua_dang_ky, "document_versions", versionId)
                .actorUserId(userId).afterState(Map.of("patch", patch)));
    }

    /** Xóa bản ban hành — chỉ khi chưa có chấp nhận nào. Xóa cả receipt (cascade). */
    @Transactional
    public void deleteUnacceptedVersion(String versionId, String userId) {
        VersionOwnerRow v = loadOwnedVersion(versionId, userId);
        if (acceptedCount(versionId) > 0) {
            throw new BusinessException("del.locked",
                    "Đã có site chấp nhận — không xóa được, phải thu hồi");
        }
        audit.writeAudit(AuditEntry.of(EventType.xoa_ban_hanh, "document_versions", versionId)
                .actorUserId(userId).afterState(Map.of("prev_status", v.status())));
        jdbc.update("DELETE FROM document_versions WHERE version_id = :v",
                new MapSqlParameterSource("v", versionId));
    }

    /** Thu hồi (Author) — khi chưa ai chấp nhận HOẶC có ≥1 từ chối (kể cả 100% chấp nhận). */
    @Transactional
    public void recallByAuthor(String versionId, String userId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("recall.reason", "Thu hồi bắt buộc kèm lý do");
        }
        VersionOwnerRow v = loadOwnedVersion(versionId, userId);
        if (!"da_ban_hanh".equals(v.status())) {
            throw new BusinessException("recall.bad_status",
                    "Không thu hồi được từ trạng thái " + v.status());
        }
        ReceiptTally tally = receiptTally(versionId);
        List<String> affectedSiteIds = jdbc.queryForList(
                "SELECT site_id FROM document_receipt WHERE version_id = :v",
                new MapSqlParameterSource("v", versionId), String.class);
        String trimmed = reason.trim();
        jdbc.update(
                "UPDATE document_versions SET status = 'thu_hoi', recall_reason = :reason, updated_at = now() "
                + "WHERE version_id = :v",
                new MapSqlParameterSource().addValue("reason", trimmed).addValue("v", versionId));
        audit.writeAudit(AuditEntry.of(EventType.thu_hoi, "document_versions", versionId)
                .actorUserId(userId)
                .afterState(Map.of("status", "thu_hoi", "reason", trimmed,
                        "tally", Map.of("accepted", tally.accepted(), "rejected", tally.rejected(),
                                "pending", tally.pending(), "total", tally.total()),
                        "affected_site_ids", affectedSiteIds)));
    }

    /**
     * Phát hành THÊM site (Author) cho tài liệu đã ban hành. Chỉ site TRONG
     * scope; chặn khi có từ chối sai_noi_dung; phát hành lại site từ chối
     * sai_dang_ky. Trả về {added, republished}.
     */
    @Transactional
    public RepublishResult publishAdditionalSites(String versionId, String userId,
            List<String> siteIds, Map<String, String> siteEffectiveDates) {
        VersionOwnerRow v = loadOwnedVersion(versionId, userId);
        if (!"da_ban_hanh".equals(v.status())) {
            throw new BusinessException("publish.bad_status",
                    "Chỉ phát hành thêm khi tài liệu đang ở trạng thái đã ban hành");
        }
        assertNotAcceptedLocked(versionId);

        List<DocScopeRow> docs = jdbc.query(
                "SELECT d.doc_id, d.scope_level, d.scope_bu_id, d.scope_site_id, d.scope_dept_id, "
                + "ver.issued_date::text AS issued_date "
                + "FROM document_versions ver JOIN documents d ON d.doc_id = ver.doc_id "
                + "WHERE ver.version_id = :v",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new DocScopeRow(rs.getString("doc_id"), rs.getString("scope_level"),
                        rs.getString("scope_bu_id"), rs.getString("scope_site_id"),
                        rs.getString("scope_dept_id"), rs.getString("issued_date")));
        if (docs.isEmpty()) {
            throw new BusinessException("publish.not_found", "Không tìm thấy tài liệu");
        }
        DocScopeRow doc = docs.get(0);

        List<SiteOption> candidates = candidateSitesForScope(doc.scopeLevel(), doc.scopeBuId(),
                doc.scopeSiteId(), doc.scopeDeptId());
        List<SiteClassify.ExistingReceipt> existing = jdbc.query(
                "SELECT site_id, status, response_type FROM document_receipt WHERE version_id = :v",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new SiteClassify.ExistingReceipt(rs.getString("site_id"),
                        rs.getString("status"), rs.getString("response_type")));

        SiteClassify.Result cls = SiteClassify.classifySitesForRepublish(new SiteClassify.Input(
                candidates.stream().map(SiteOption::siteId).toList(), existing, siteIds));

        if (cls.blockedByContentReject()) {
            throw new BusinessException("publish.content_reject",
                    "Có site báo sai nội dung — phải thu hồi và ban hành phiên bản mới, không phát hành thêm");
        }
        if (!cls.invalid().isEmpty()) {
            throw new BusinessException("publish.out_of_scope", "Có site không thuộc phạm vi của tài liệu");
        }
        if (cls.newOnes().isEmpty() && cls.republish().isEmpty()) {
            throw new BusinessException("publish.nothing", "Không có site hợp lệ để phát hành thêm");
        }

        String today = Clock.isoTodayVN();
        String issued = doc.issuedDate() == null ? null : LifecycleStatus.slice10(doc.issuedDate());
        Map<String, String> effDates = siteEffectiveDates == null ? Map.of() : siteEffectiveDates;
        List<String> toCheck = new ArrayList<>(cls.newOnes());
        toCheck.addAll(cls.republish());
        for (String siteId : toCheck) {
            String eff = effDates.get(siteId);
            if (eff == null) {
                continue;
            }
            String eff10 = LifecycleStatus.slice10(eff);
            if (eff10.compareTo(today) < 0) {
                throw new BusinessException("publish.effective_past",
                        "Ngày hiệu lực của site không được đặt về quá khứ");
            }
            if (issued != null && eff10.compareTo(issued) < 0) {
                throw new BusinessException("publish.effective_before_issued",
                        "Ngày hiệu lực của site không được sớm hơn ngày ban hành");
            }
        }

        String targetDeptId = "dept".equals(doc.scopeLevel()) ? doc.scopeDeptId() : null;
        int added = 0;
        if (!cls.newOnes().isEmpty()) {
            added = publishToSites(versionId, cls.newOnes(), targetDeptId, userId, siteEffectiveDates);
        }
        int republished = 0;
        if (!cls.republish().isEmpty()) {
            for (String siteId : cls.republish()) {
                String siteEff = effDates.get(siteId);
                jdbc.update(
                        "UPDATE document_receipt SET status = 'cho_phan_hoi', response_type = NULL, "
                        + "response_note = NULL, responded_by = NULL, responded_at = NULL, apply_date = NULL, "
                        + "site_effective_date = CAST(:eff AS date), updated_at = now() "
                        + "WHERE version_id = :v AND site_id = :site",
                        new MapSqlParameterSource().addValue("eff", siteEff)
                                .addValue("v", versionId).addValue("site", siteId));
            }
            republished = cls.republish().size();
            audit.writeAudit(AuditEntry.of(EventType.ban_hanh, "document_versions", versionId)
                    .actorUserId(userId)
                    .afterState(Map.of("action", "re_publish", "site_ids", cls.republish(),
                            "prev_reject", "sai_dang_ky")));
        }
        return new RepublishResult(added, republished);
    }

    public record RepublishResult(int added, int republished) {
    }

    private record DocScopeRow(String docId, String scopeLevel, String scopeBuId, String scopeSiteId,
            String scopeDeptId, String issuedDate) {
    }

    /** Xây literal mảng uuid Postgres từ danh sách id (dùng với CAST(... AS uuid[])). */
    private static String toUuidArrayLiteral(List<String> ids) {
        return "{" + String.join(",", ids) + "}";
    }
}
