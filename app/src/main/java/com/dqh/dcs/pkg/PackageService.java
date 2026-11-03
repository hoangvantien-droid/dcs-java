package com.dqh.dcs.pkg;

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

/**
 * pkg/PackageService — gói ban hành [Legacy] (port src/domain/package.ts,
 * T-078..081).
 *
 * Người đăng ký tạo gói. Tối đa 10 thành viên (trigger DB ép). Ngày hiệu lực
 * nằm TRÊN gói, thành viên thừa kế. Dời ngày chỉ ra xa.
 */
@Service
public class PackageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public PackageService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record PackageRow(String packageId, String packageCode, String title, String effectiveDate,
            String status, int memberCount, int confirmedCount) {
    }

    public record MemberRow(String versionId, String docId, String versionNo, String title, String status) {
    }

    public record CreatePackageInput(String code, String title, String effectiveDate, String createdBy) {
    }

    public List<PackageRow> listPackages() {
        return jdbc.query(
                "SELECT p.package_id, p.package_code, p.title, p.effective_date::text AS effective_date, p.status, "
                + "(SELECT count(*)::int FROM document_versions m WHERE m.package_id = p.package_id) AS member_count, "
                + "(SELECT count(*)::int FROM document_versions m WHERE m.package_id = p.package_id AND m.confirmed_at IS NOT NULL) AS confirmed_count "
                + "FROM release_packages p ORDER BY p.created_at DESC",
                new MapSqlParameterSource(),
                (rs, i) -> new PackageRow(rs.getString("package_id"), rs.getString("package_code"),
                        rs.getString("title"), rs.getString("effective_date"), rs.getString("status"),
                        rs.getInt("member_count"), rs.getInt("confirmed_count")));
    }

    public List<MemberRow> packageMembers(String packageId) {
        return jdbc.query(
                "SELECT version_id, doc_id, version_no, title, status FROM document_versions "
                + "WHERE package_id = CAST(:p AS uuid) ORDER BY doc_id",
                new MapSqlParameterSource("p", packageId),
                (rs, i) -> new MemberRow(rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("status")));
    }

    /** Đăng ký của tôi chưa thuộc gói và có ngày riêng — để gắn vào gói. */
    public List<MemberRow> attachableFor(String userId) {
        return jdbc.query(
                "SELECT version_id, doc_id, version_no, title, status FROM document_versions "
                + "WHERE registrant_user_id = CAST(:u AS uuid) AND package_id IS NULL "
                + "AND status IN ('cho_xac_nhan','da_dang_ky') ORDER BY doc_id",
                new MapSqlParameterSource("u", userId),
                (rs, i) -> new MemberRow(rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("status")));
    }

    @Transactional
    public String createPackage(CreatePackageInput input) {
        Boolean isAuthor = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles WHERE user_id = CAST(:u AS uuid) "
                + "AND role_code = 'DC_AUTHOR' AND is_active) AS ok",
                new MapSqlParameterSource("u", input.createdBy()), Boolean.class);
        if (!Boolean.TRUE.equals(isAuthor)) {
            throw new BusinessException("pkg.no_permission", "Chỉ người đăng ký ban hành được tạo gói", 403);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO release_packages (package_id, package_code, title, effective_date, created_by) "
                + "VALUES (CAST(:id AS uuid), :code, :title, CAST(:eff AS date), CAST(:by AS uuid))",
                new MapSqlParameterSource().addValue("id", id).addValue("code", input.code())
                        .addValue("title", input.title()).addValue("eff", input.effectiveDate())
                        .addValue("by", input.createdBy()));
        audit.writeAudit(AuditEntry.of(EventType.tao_goi, "release_packages", id)
                .actorUserId(input.createdBy())
                .afterState(Map.of("code", input.code(), "effective_date", input.effectiveDate())));
        return id;
    }

    @Transactional
    public void attachToPackage(String versionId, String packageId, String userId) {
        List<String[]> vrows = jdbc.query(
                "SELECT status, registrant_user_id FROM document_versions WHERE version_id = CAST(:v AS uuid) FOR UPDATE",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new String[] { rs.getString("status"), rs.getString("registrant_user_id") });
        if (vrows.isEmpty()) {
            throw new BusinessException("pkg.no_version", "Không tìm thấy phiên bản");
        }
        List<String> pkg = jdbc.queryForList(
                "SELECT package_id FROM release_packages WHERE package_id = CAST(:p AS uuid) FOR UPDATE",
                new MapSqlParameterSource("p", packageId), String.class);
        if (pkg.isEmpty()) {
            throw new BusinessException("pkg.not_found", "Không tìm thấy gói ban hành");
        }
        String status = vrows.get(0)[0];
        String registrant = vrows.get(0)[1];
        if (!userId.equals(registrant)) {
            throw new BusinessException("pkg.no_permission",
                    "Chỉ người đăng ký được gắn phiên bản của mình vào gói", 403);
        }
        if (!List.of("cho_xac_nhan", "da_dang_ky").contains(status)) {
            throw new BusinessException("pkg.bad_status",
                    "Chỉ gắn được đăng ký đang chờ xác nhận hoặc đã đăng ký");
        }
        jdbc.update(
                "UPDATE document_versions SET package_id = CAST(:p AS uuid), effective_date = NULL "
                + "WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource().addValue("p", packageId).addValue("v", versionId));
        audit.writeAudit(AuditEntry.of(EventType.gan_vao_goi, "document_versions", versionId)
                .actorUserId(userId).afterState(Map.of("package_id", packageId)));
    }

    @Transactional
    public void movePackageDate(String packageId, String newDate, String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT created_by FROM release_packages WHERE package_id = CAST(:p AS uuid) FOR UPDATE",
                new MapSqlParameterSource("p", packageId), String.class);
        if (rows.isEmpty()) {
            throw new BusinessException("pkg.not_found", "Không tìm thấy gói ban hành");
        }
        if (!userId.equals(rows.get(0))) {
            throw new BusinessException("pkg.no_permission", "Chỉ người tạo gói được dời ngày hiệu lực", 403);
        }
        jdbc.update(
                "UPDATE release_packages SET effective_date = CAST(:eff AS date) WHERE package_id = CAST(:p AS uuid)",
                new MapSqlParameterSource().addValue("eff", newDate).addValue("p", packageId));
        audit.writeAudit(AuditEntry.of(EventType.doi_ngay_goi, "release_packages", packageId)
                .actorUserId(userId).afterState(Map.of("effective_date", newDate)));
    }

    @Transactional
    public void detachMember(String versionId, String reason, String userId) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("pkg.reason", "Tháo thành viên bắt buộc ghi lý do");
        }
        List<String[]> rows = jdbc.query(
                "SELECT dv.package_id, rp.effective_date::text AS effective_date, rp.created_by "
                + "FROM document_versions dv LEFT JOIN release_packages rp ON rp.package_id = dv.package_id "
                + "WHERE dv.version_id = CAST(:v AS uuid) FOR UPDATE OF dv",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new String[] { rs.getString("package_id"), rs.getString("effective_date"),
                        rs.getString("created_by") });
        String packageId = rows.isEmpty() ? null : rows.get(0)[0];
        if (packageId == null) {
            throw new BusinessException("pkg.not_member", "Phiên bản không thuộc gói nào");
        }
        String effectiveDate = rows.get(0)[1];
        String createdBy = rows.get(0)[2];
        if (!userId.equals(createdBy)) {
            throw new BusinessException("pkg.no_permission", "Chỉ người tạo gói được tháo thành viên", 403);
        }
        jdbc.update(
                "UPDATE document_versions SET package_id = NULL, effective_date = CAST(:eff AS date) "
                + "WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource().addValue("eff", effectiveDate).addValue("v", versionId));
        audit.writeAudit(AuditEntry.of(EventType.thao_thanh_vien, "document_versions", versionId)
                .actorUserId(userId)
                .afterState(Map.of("reason", reason, "restored_effective_date",
                        effectiveDate == null ? "" : effectiveDate)));
    }
}
