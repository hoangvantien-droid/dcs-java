package com.dqh.dcs.catalog;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.dqh.dcs.lifecycle.LifecycleStatus;

/**
 * catalog/CatalogService — danh mục và chi tiết tài liệu (port src/domain/catalog.ts,
 * T-037, T-038).
 *
 * Danh mục lọc theo quyền: lọc TRƯỚC bằng điều kiện truy vấn, không lọc sau
 * (DCS-2 §7.1). Người chỉ-xem (Reader): chỉ bản hiện hành (da_ban_hanh/hieu_luc)
 * đã tới ngày hiệu lực.
 */
@Service
public class CatalogService {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CatalogRow(String docId, String versionId, String versionNo, String title, String status,
            String docType, String scopeLevel, String origin, String effectiveDate, String nextReviewDate,
            String issuedDate, String expiryDate, String ownerDepartmentId) {
    }

    public record CatalogFilters(String status, String q, String docType, String origin, String effFrom,
            String effTo, String preset, String sort, String siteId, String rolloutStatus) {
        public static CatalogFilters empty() {
            return new CatalogFilters(null, null, null, null, null, null, null, "hieu_luc_moi", null, null);
        }
    }

    public record DocumentDetail(String docId, String versionId, String versionNo, String title, String status,
            String registrantUserId, String docType, String scopeLevel, String origin, String scopeBuId,
            String scopeSiteId, String scopeDeptId, String effectiveDate, String nextReviewDate,
            String ownerDepartmentId, String issuedDate, String expiryDate, String rejectionReason,
            String recallReason, boolean quaHanXemXet, boolean quaHanSuDung) {
    }

    public record SectionRow(String sectionCode, String sectionTitle, String rawText) {
    }

    public record LinkRow(String linkType, String linkCode, String sectionId) {
    }

    public record VersionRef(String versionId, String versionNo) {
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Trạng thái được coi là "hiện hành" (Reader được phép xem). Port isCurrentStatus. */
    public static boolean isCurrentStatus(String status) {
        return LifecycleStatus.isCurrentlyEffective(status);
    }

    /**
     * Danh mục tài liệu người dùng được thấy. readOnlyViewer=true (Reader): CHỈ
     * hiện tài liệu hiện hành (da_ban_hanh/hieu_luc) đã tới ngày hiệu lực.
     */
    public List<CatalogRow> listVisibleDocuments(String userId, CatalogFilters f, boolean readOnlyViewer) {
        if (f == null) {
            f = CatalogFilters.empty();
        }
        String status = trimOrNull(f.status());
        String q = trimOrNull(f.q());
        String docType = trimOrNull(f.docType());
        String origin = trimOrNull(f.origin());
        String effFrom = trimOrNull(f.effFrom());
        String effTo = trimOrNull(f.effTo());
        String preset = trimOrNull(f.preset());
        String siteId = trimOrNull(f.siteId());
        String rolloutStatus = trimOrNull(f.rolloutStatus());
        String sort = trimOrNull(f.sort());
        if (sort == null) {
            sort = "hieu_luc_moi";
        }
        String presetStatus = "hieu_luc".equals(preset) ? "hieu_luc" : null;
        String effStatus = status != null ? status : presetStatus;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("read_only", readOnlyViewer)
                .addValue("eff_status", effStatus)
                .addValue("doc_type", docType)
                .addValue("origin", origin)
                .addValue("eff_from", effFrom)
                .addValue("eff_to", effTo)
                .addValue("q", q)
                .addValue("preset", preset)
                .addValue("site_id", siteId)
                .addValue("rollout_status", rolloutStatus)
                .addValue("sort", sort);
        return jdbc.query(
                "SELECT v.doc_id, v.version_id, v.version_no, v.title, v.status, "
                + "d.doc_type, d.scope_level, d.origin, "
                + "v.effective_date::text AS effective_date, v.next_review_date::text AS next_review_date, "
                + "v.owner_department_id, v.issued_date::text AS issued_date, v.expiry_date::text AS expiry_date "
                + "FROM document_versions v JOIN documents d ON d.doc_id = v.doc_id "
                + "WHERE ("
                + "  v.registrant_user_id = CAST(:user_id AS uuid) "
                + "  OR d.scope_level = 'group' "
                + "  OR EXISTS (SELECT 1 FROM user_departments ud "
                + "    JOIN org_department dp ON dp.dept_id = ud.dept_id "
                + "    JOIN org_site st ON st.site_id = dp.site_id "
                + "    WHERE ud.user_id = CAST(:user_id AS uuid) AND ud.is_active "
                + "    AND ((d.scope_level='bu' AND EXISTS (SELECT 1 FROM org_site_bu sb "
                + "          WHERE sb.site_id = st.site_id AND sb.bu_id = d.scope_bu_id)) "
                + "      OR (d.scope_level='site' AND st.site_id = d.scope_site_id) "
                + "      OR (d.scope_level='dept' AND dp.dept_id = d.scope_dept_id))) "
                + "  OR EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = CAST(:user_id AS uuid) "
                + "    AND r.is_active AND r.role_code = 'DC_ADMIN')) "
                + "AND (NOT :read_only OR (v.status IN ('da_ban_hanh', 'hieu_luc') AND v.effective_date <= CURRENT_DATE)) "
                + "AND (CAST(:eff_status AS varchar) IS NULL OR v.status = :eff_status) "
                + "AND (CAST(:doc_type AS varchar) IS NULL OR d.doc_type = :doc_type) "
                + "AND (CAST(:origin AS varchar) IS NULL OR d.origin = :origin) "
                + "AND (CAST(:eff_from AS date) IS NULL OR v.effective_date >= CAST(:eff_from AS date)) "
                + "AND (CAST(:eff_to AS date) IS NULL OR v.effective_date <= CAST(:eff_to AS date)) "
                + "AND (CAST(:q AS text) IS NULL OR v.doc_id ILIKE '%' || :q || '%' OR v.title ILIKE '%' || :q || '%' "
                + "  OR COALESCE(array_to_string(v.keywords, ' '), '') ILIKE '%' || :q || '%') "
                + "AND ((CAST(:preset AS varchar) IS NULL OR :preset = 'hieu_luc') "
                + "  OR (:preset = 'new30' AND v.effective_date >= CURRENT_DATE - INTERVAL '30 days') "
                + "  OR (:preset = 'expiring30' AND v.expiry_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days') "
                + "  OR (:preset = 'overdue' AND v.next_review_date IS NOT NULL AND v.next_review_date < CURRENT_DATE) "
                + "  OR (:preset = 'expired' AND v.expiry_date IS NOT NULL AND v.expiry_date < CURRENT_DATE)) "
                + "AND (CAST(:site_id AS uuid) IS NULL OR EXISTS (SELECT 1 FROM document_receipt rc "
                + "  WHERE rc.version_id = v.version_id AND rc.site_id = CAST(:site_id AS uuid))) "
                + "AND (CAST(:rollout_status AS varchar) IS NULL OR EXISTS (SELECT 1 FROM document_receipt rc "
                + "  WHERE rc.version_id = v.version_id AND (CAST(:site_id AS uuid) IS NULL OR rc.site_id = CAST(:site_id AS uuid)) "
                + "  AND rc.status = CASE :rollout_status WHEN 'chua_ap_dung' THEN 'cho_phan_hoi' "
                + "    WHEN 'da_ap_dung' THEN 'chap_nhan' WHEN 'tam_hoan' THEN 'tu_choi' ELSE :rollout_status END)) "
                + "ORDER BY "
                + "  CASE WHEN :sort = 'ban_hanh_moi' THEN v.issued_date END DESC NULLS LAST, "
                + "  CASE WHEN :sort = 'sap_het_han' THEN v.expiry_date END ASC NULLS LAST, "
                + "  CASE WHEN :sort = 'so_hieu' THEN v.doc_id END ASC, "
                + "  v.effective_date DESC NULLS LAST, v.doc_id",
                p,
                (rs, i) -> new CatalogRow(rs.getString("doc_id"), rs.getString("version_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("status"),
                        rs.getString("doc_type"), rs.getString("scope_level"), rs.getString("origin"),
                        rs.getString("effective_date"), rs.getString("next_review_date"),
                        rs.getString("issued_date"), rs.getString("expiry_date"),
                        rs.getString("owner_department_id")));
    }

    /** Chi tiết một phiên bản. */
    public DocumentDetail loadDetail(String versionId) {
        List<DocumentDetail> rows = jdbc.query(
                "SELECT v.doc_id, v.version_id, v.version_no, v.title, v.status, v.registrant_user_id, "
                + "d.doc_type, d.scope_level, d.origin, d.scope_bu_id, d.scope_site_id, d.scope_dept_id, "
                + "v.effective_date::text AS effective_date, v.next_review_date::text AS next_review_date, "
                + "v.owner_department_id, v.issued_date::text AS issued_date, v.expiry_date::text AS expiry_date, "
                + "v.rejection_reason, v.recall_reason, "
                + "(v.next_review_date IS NOT NULL AND v.next_review_date < CURRENT_DATE) AS qua_han_xem_xet, "
                + "(v.expiry_date IS NOT NULL AND v.expiry_date < CURRENT_DATE) AS qua_han_su_dung "
                + "FROM document_versions v JOIN documents d ON d.doc_id = v.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new DocumentDetail(rs.getString("doc_id"), rs.getString("version_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("status"),
                        rs.getString("registrant_user_id"), rs.getString("doc_type"), rs.getString("scope_level"),
                        rs.getString("origin"), rs.getString("scope_bu_id"), rs.getString("scope_site_id"),
                        rs.getString("scope_dept_id"), rs.getString("effective_date"),
                        rs.getString("next_review_date"), rs.getString("owner_department_id"),
                        rs.getString("issued_date"), rs.getString("expiry_date"), rs.getString("rejection_reason"),
                        rs.getString("recall_reason"), rs.getBoolean("qua_han_xem_xet"),
                        rs.getBoolean("qua_han_su_dung")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean canViewVersion(String userId, String versionId, boolean readOnlyViewer) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document_versions v JOIN documents d ON d.doc_id = v.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid) "
                + "AND (NOT :read_only OR (v.status IN ('da_ban_hanh', 'hieu_luc') AND v.effective_date <= CURRENT_DATE)) "
                + "AND (v.registrant_user_id = CAST(:u AS uuid) OR d.scope_level = 'group' "
                + "  OR EXISTS (SELECT 1 FROM user_departments ud "
                + "    JOIN org_department dp ON dp.dept_id = ud.dept_id "
                + "    JOIN org_site st ON st.site_id = dp.site_id "
                + "    WHERE ud.user_id = CAST(:u AS uuid) AND ud.is_active "
                + "    AND ((d.scope_level = 'bu' AND EXISTS (SELECT 1 FROM org_site_bu sb "
                + "          WHERE sb.site_id = st.site_id AND sb.bu_id = d.scope_bu_id)) "
                + "      OR (d.scope_level = 'site' AND st.site_id = d.scope_site_id) "
                + "      OR (d.scope_level = 'dept' AND dp.dept_id = d.scope_dept_id))) "
                + "  OR EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = CAST(:u AS uuid) "
                + "    AND r.is_active AND r.role_code = 'DC_ADMIN'))) AS ok",
                new MapSqlParameterSource().addValue("u", userId).addValue("v", versionId)
                        .addValue("read_only", readOnlyViewer),
                Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    public boolean canViewVersion(String userId, String versionId) {
        return canViewVersion(userId, versionId, false);
    }

    public List<SectionRow> loadSections(String versionId) {
        return jdbc.query(
                "SELECT section_code, section_title, raw_text FROM document_sections "
                + "WHERE version_id = CAST(:v AS uuid) ORDER BY order_no",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new SectionRow(rs.getString("section_code"), rs.getString("section_title"),
                        rs.getString("raw_text")));
    }

    public List<LinkRow> loadLinks(String versionId) {
        return jdbc.query(
                "SELECT link_type, link_code, section_id FROM document_links "
                + "WHERE version_id = CAST(:v AS uuid) ORDER BY link_type, link_code",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new LinkRow(rs.getString("link_type"), rs.getString("link_code"),
                        rs.getString("section_id")));
    }

    /** Phiên bản hiện hành của cùng doc_id (để trỏ khi xem bản hết hiệu lực). */
    public VersionRef currentVersionOf(String docId) {
        List<VersionRef> rows = jdbc.query(
                "SELECT version_id, version_no FROM document_versions "
                + "WHERE doc_id = :d AND status IN ('da_ban_hanh', 'hieu_luc') LIMIT 1",
                new MapSqlParameterSource("d", docId),
                (rs, i) -> new VersionRef(rs.getString("version_id"), rs.getString("version_no")));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
