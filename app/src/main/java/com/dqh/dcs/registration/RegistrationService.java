package com.dqh.dcs.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.audit.AuditEntry;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.audit.EventType;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.common.Clock;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.roles.ScopeLevel;

/**
 * registration/RegistrationService — đăng ký tài liệu (port src/domain/registration.ts,
 * T-020 → T-026). Form đăng ký là cổng vào DUY NHẤT đưa tài liệu vào trạng thái
 * đã ban hành. KHÔNG bọc try-catch nuốt lỗi ràng buộc DB (DCS-9 bẫy số 1).
 */
@Service
public class RegistrationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final PermissionService permissions;
    private final ReceiptService receipts;

    public RegistrationService(NamedParameterJdbcTemplate jdbc, AuditService audit,
            PermissionService permissions, ReceiptService receipts) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.permissions = permissions;
        this.receipts = receipts;
    }

    /**
     * Chuẩn hoá phạm vi tài liệu theo scope_level (thuần — port normalizeDocumentScope).
     * Giữ đúng một con trỏ theo cấp; '' → NULL (tránh 22P02); thiếu con trỏ bắt
     * buộc thì ném BusinessException (không để rơi xuống lỗi ràng buộc DB).
     * Đây là ĐIỂM DUY NHẤT xử lý cho cả /register và /register/draft.
     */
    public static DocumentInput normalizeDocumentScope(DocumentInput doc) {
        String bu = clean(doc.scopeBuId());
        String site = clean(doc.scopeSiteId());
        String dept = clean(doc.scopeDeptId());
        if (doc.scopeLevel() == null) {
            throw new BusinessException("reg.scope_level", "Cấp phạm vi không hợp lệ: null");
        }
        switch (doc.scopeLevel()) {
            case group:
                return new DocumentInput(doc.docId(), doc.docType(), doc.origin(),
                        ScopeLevel.group, null, null, null);
            case bu:
                if (bu == null) {
                    throw new BusinessException("reg.scope_bu",
                            "Phạm vi cấp Ngành hàng (BU) bắt buộc chọn BU");
                }
                return new DocumentInput(doc.docId(), doc.docType(), doc.origin(),
                        ScopeLevel.bu, bu, null, null);
            case site:
                if (site == null) {
                    throw new BusinessException("reg.scope_site",
                            "Phạm vi cấp Nhà máy (Site) bắt buộc chọn Site");
                }
                return new DocumentInput(doc.docId(), doc.docType(), doc.origin(),
                        ScopeLevel.site, null, site, null);
            case dept:
                if (dept == null) {
                    throw new BusinessException("reg.scope_dept",
                            "Phạm vi cấp Bộ phận bắt buộc chọn Bộ phận");
                }
                return new DocumentInput(doc.docId(), doc.docType(), doc.origin(),
                        ScopeLevel.dept, null, null, dept);
            default:
                throw new BusinessException("reg.scope_level",
                        "Cấp phạm vi không hợp lệ: " + doc.scopeLevel());
        }
    }

    private static String clean(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Tạo bản ghi documents nếu chưa có (doc_id do người nhập, không tự sinh). */
    void ensureDocument(DocumentInput doc) {
        List<String> existing = jdbc.queryForList(
                "SELECT doc_id FROM documents WHERE doc_id = :id",
                new MapSqlParameterSource("id", doc.docId()), String.class);
        if (!existing.isEmpty()) {
            return;
        }
        DocumentInput d = normalizeDocumentScope(doc);
        jdbc.update(
                "INSERT INTO documents (doc_id, doc_type, origin, scope_level, scope_bu_id, scope_site_id, scope_dept_id) "
                + "VALUES (:doc_id, :doc_type, :origin, :scope_level, "
                + "CAST(:scope_bu_id AS uuid), CAST(:scope_site_id AS uuid), CAST(:scope_dept_id AS uuid))",
                new MapSqlParameterSource()
                        .addValue("doc_id", d.docId())
                        .addValue("doc_type", d.docType())
                        .addValue("origin", d.origin())
                        .addValue("scope_level", d.scopeLevel().name())
                        .addValue("scope_bu_id", d.scopeBuId())
                        .addValue("scope_site_id", d.scopeSiteId())
                        .addValue("scope_dept_id", d.scopeDeptId()));
    }

    /** Kiểm tra khi nộp (DCS-3 §C.2, mức Chặn). Ném BusinessException nếu vi phạm. */
    void validateForSubmit(VersionInput v) {
        List<String> originRows = jdbc.queryForList(
                "SELECT origin FROM documents WHERE doc_id = :id",
                new MapSqlParameterSource("id", v.docId()), String.class);
        if (originRows.isEmpty()) {
            throw new BusinessException("reg.no_doc", "Chưa có bản ghi tài liệu cho số hiệu này");
        }
        String origin = originRows.get(0);
        if ("ngoai_bo".equals(origin) && (v.externalEffectiveDate() == null || v.externalEffectiveDate().isEmpty())) {
            throw new BusinessException("reg.ext_date",
                    "Tài liệu ngoại bộ bắt buộc có ngày hiệu lực pháp lý");
        }

        List<Integer> dup = jdbc.queryForList(
                "SELECT 1 FROM document_versions WHERE doc_id = :id AND version_no = :vn",
                new MapSqlParameterSource().addValue("id", v.docId()).addValue("vn", v.versionNo()),
                Integer.class);
        if (!dup.isEmpty()) {
            throw new BusinessException("reg.duplicate",
                    "Số hiệu " + v.docId() + " phiên bản " + v.versionNo() + " đã tồn tại");
        }

        String today = Clock.isoTodayVN();
        if (v.effectiveDate() != null && v.effectiveDate().compareTo(today) < 0) {
            throw new BusinessException("reg.effective_past", "Ngày hiệu lực phải từ " + today + " trở đi");
        }

        if (v.expiryDate() != null && !v.expiryDate().isEmpty() && v.effectiveDate() != null
                && v.expiryDate().compareTo(v.effectiveDate()) <= 0) {
            throw new BusinessException("reg.expiry", "Ngày hết hạn phải sau ngày hiệu lực");
        }

        if (v.supersedesVersionId() != null && !v.supersedesVersionId().isEmpty()) {
            List<String> supRows = jdbc.queryForList(
                    "SELECT status FROM document_versions WHERE version_id = :v",
                    new MapSqlParameterSource("v", v.supersedesVersionId()), String.class);
            String sup = supRows.isEmpty() ? null : supRows.get(0);
            if (sup == null || !List.of("da_ban_hanh", "hieu_luc", "da_dang_ky").contains(sup)) {
                throw new BusinessException("reg.supersedes",
                        "Phiên bản gốc phải đang Đã ban hành, Hiệu lực hoặc Đã đăng ký");
            }
            if (v.changeSignificance() == null || v.changeSignificance().isEmpty()) {
                throw new BusinessException("reg.change_sig",
                        "Phiên bản mới bắt buộc khai mức độ thay đổi");
            }
            if ("lon".equals(v.changeSignificance())
                    && (v.changeSummary() == null || v.changeSummary().isEmpty())) {
                throw new BusinessException("reg.change_summary", "Thay đổi lớn bắt buộc có tóm tắt");
            }
        }
    }

    /**
     * Nộp đăng ký: tạo document (nếu cần) + document_versions ở da_ban_hanh, rồi
     * sinh receipt cho các site nhận (ban hành thẳng). Trả về version_id.
     */
    @Transactional
    public String submitRegistration(DocumentInput doc, VersionInput v, List<String> siteIds) {
        if (!permissions.canRegisterDocuments(v.registrantUserId())) {
            throw new BusinessException("reg.no_permission", "Không có quyền đăng ký ban hành tài liệu", 403);
        }
        ensureDocument(doc);
        validateForSubmit(v);

        List<String> sites = new ArrayList<>();
        if (siteIds != null) {
            for (String s : siteIds) {
                if (s != null && !s.trim().isEmpty()) {
                    sites.add(s);
                }
            }
        }
        if (sites.isEmpty()) {
            throw new BusinessException("reg.no_site", "Phải chọn ít nhất một site nhận tài liệu");
        }

        String versionId = UUID.randomUUID().toString();
        String ownerDeptId = clean(v.ownerDepartmentId());

        boolean hasSupersede = v.supersedesVersionId() != null && !v.supersedesVersionId().isEmpty();
        if (hasSupersede) {
            jdbc.update(
                    "UPDATE document_versions SET status = 'het_hieu_luc', updated_at = now() "
                    + "WHERE version_id = :v AND status IN ('da_ban_hanh', 'hieu_luc', 'da_dang_ky')",
                    new MapSqlParameterSource("v", v.supersedesVersionId()));
            audit.writeAudit(AuditEntry.of(EventType.chuyen_het_hieu_luc, "document_versions",
                            v.supersedesVersionId())
                    .actorUserId(v.registrantUserId())
                    .afterState(Map.of("status", "het_hieu_luc", "superseded_by", versionId)));
        }

        jdbc.update(
                "INSERT INTO document_versions ("
                + "version_id, doc_id, version_no, title, issued_date, "
                + "external_effective_date, effective_date, review_cycle_months, expiry_date, "
                + "source_channel, file_path, file_hash, status, registrant_user_id, owner_department_id, "
                + "supersedes_version_id, change_significance, change_summary, submitted_at) "
                + "VALUES ("
                + "CAST(:version_id AS uuid), :doc_id, :version_no, :title, CAST(:issued_date AS date), "
                + "CAST(:ext_eff AS date), CAST(:effective_date AS date), :review_cycle_months, CAST(:expiry_date AS date), "
                + ":source_channel, :file_path, :file_hash, 'da_ban_hanh', CAST(:registrant AS uuid), CAST(:owner_dept AS uuid), "
                + "CAST(:supersedes AS uuid), :change_sig, :change_summary, now())",
                new MapSqlParameterSource()
                        .addValue("version_id", versionId)
                        .addValue("doc_id", v.docId())
                        .addValue("version_no", v.versionNo())
                        .addValue("title", v.title())
                        .addValue("issued_date", v.issuedDate())
                        .addValue("ext_eff", emptyToNull(v.externalEffectiveDate()))
                        .addValue("effective_date", v.effectiveDate())
                        .addValue("review_cycle_months", v.reviewCycleMonths())
                        .addValue("expiry_date", emptyToNull(v.expiryDate()))
                        .addValue("source_channel", v.sourceChannel())
                        .addValue("file_path", v.filePath())
                        .addValue("file_hash", v.fileHash())
                        .addValue("registrant", v.registrantUserId())
                        .addValue("owner_dept", ownerDeptId)
                        .addValue("supersedes", emptyToNull(v.supersedesVersionId()))
                        .addValue("change_sig", emptyToNull(v.changeSignificance()))
                        .addValue("change_summary", emptyToNull(v.changeSummary())));

        audit.writeAudit(AuditEntry.of(EventType.nop_dang_ky, "document_versions", versionId)
                .actorUserId(v.registrantUserId())
                .afterState(Map.of("doc_id", v.docId(), "version_no", v.versionNo(), "status", "da_ban_hanh")));

        if (hasSupersede && "nho".equals(v.changeSignificance())) {
            jdbc.update(
                    "INSERT INTO read_acknowledgements "
                    + "(version_id, user_id, department_id, functional_group_id, acknowledged_at, inherited_from_version_id) "
                    + "SELECT CAST(:new_v AS uuid), a.user_id, a.department_id, a.functional_group_id, "
                    + "a.acknowledged_at, a.version_id FROM read_acknowledgements a "
                    + "WHERE a.version_id = CAST(:old_v AS uuid) "
                    + "ON CONFLICT (version_id, user_id) DO NOTHING",
                    new MapSqlParameterSource().addValue("new_v", versionId)
                            .addValue("old_v", v.supersedesVersionId()));
        }

        String targetDeptId = doc.scopeLevel() == ScopeLevel.dept ? doc.scopeDeptId() : null;
        receipts.publishToSites(versionId, sites, targetDeptId, v.registrantUserId(), null);
        return versionId;
    }

    /**
     * Lưu nháp: tạo bản ghi ở cho_hoan_thien, KHÔNG chạy kiểm tra nộp đầy đủ
     * (T-024). Trả về version_id.
     */
    @Transactional
    public String saveDraft(DocumentInput doc, VersionInput v) {
        if (!permissions.canRegisterDocuments(v.registrantUserId())) {
            throw new BusinessException("reg.no_permission", "Không có quyền lưu nháp đăng ký tài liệu", 403);
        }
        ensureDocument(doc);
        String versionId = UUID.randomUUID().toString();
        String ownerDeptId = clean(v.ownerDepartmentId());
        String docId = (v.docId() == null || v.docId().isEmpty()) ? doc.docId() : v.docId();
        String today = Clock.isoTodayVN();
        jdbc.update(
                "INSERT INTO document_versions ("
                + "version_id, doc_id, version_no, title, issued_date, "
                + "effective_date, review_cycle_months, source_channel, file_path, file_hash, "
                + "status, registrant_user_id, owner_department_id) "
                + "VALUES ("
                + "CAST(:version_id AS uuid), :doc_id, :version_no, :title, CAST(:issued_date AS date), "
                + "CAST(:effective_date AS date), :review_cycle_months, 'thu_cong', :file_path, :file_hash, "
                + "'cho_hoan_thien', CAST(:registrant AS uuid), CAST(:owner_dept AS uuid))",
                new MapSqlParameterSource()
                        .addValue("version_id", versionId)
                        .addValue("doc_id", docId)
                        .addValue("version_no", v.versionNo())
                        .addValue("title", v.title())
                        .addValue("issued_date", v.issuedDate() == null ? today : v.issuedDate())
                        .addValue("effective_date", v.effectiveDate() == null ? today : v.effectiveDate())
                        .addValue("review_cycle_months", v.reviewCycleMonths() == 0 ? 12 : v.reviewCycleMonths())
                        .addValue("file_path", v.filePath())
                        .addValue("file_hash", v.fileHash())
                        .addValue("registrant", v.registrantUserId())
                        .addValue("owner_dept", ownerDeptId));
        audit.writeAudit(AuditEntry.of(EventType.sua_truoc_khi_nop, "document_versions", versionId)
                .actorUserId(v.registrantUserId())
                .afterState(Map.of("status", "cho_hoan_thien")));
        return versionId;
    }

    private static String emptyToNull(String v) {
        if (v == null) {
            return null;
        }
        return v.isEmpty() ? null : v;
    }
}
