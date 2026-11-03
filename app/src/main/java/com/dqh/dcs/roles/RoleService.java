package com.dqh.dcs.roles;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.dqh.dcs.common.BusinessException;

/**
 * roles/RoleService — gán vai trò theo phạm vi Ver2 (port src/domain/roles.ts, T-015).
 *
 * DCS là nguồn sở hữu vai trò. Role các vai hệ thống (Admin/Author/Reader) gán
 * ở cấp Group; DC_SITE_DOC_CONTROL gán theo SITE. Vai cũ gác cổng chặn tường minh.
 */
@Service
public class RoleService {

    private final NamedParameterJdbcTemplate jdbc;

    public RoleService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record NormalizedScope(String level, String bu, String site, String dept) {
    }

    /** Chuẩn hoá phạm vi theo vai (thuần) — tái hiện normalizeScope của TS. */
    static NormalizedScope normalizeScope(RoleCode role, ScopeRef scope) {
        // Admin / Author / Reader: vai hệ thống, gán ở cấp Group.
        if (role == RoleCode.DC_ADMIN || role == RoleCode.DC_AUTHOR || role == RoleCode.DC_READER) {
            if (scope.level() != ScopeLevel.group) {
                throw new BusinessException("role.scope", role + " chỉ được gán ở cấp Group");
            }
            return new NormalizedScope("group", null, null, null);
        }
        if (role == RoleCode.DC_SITE_DOC_CONTROL) {
            String site = scope.siteId() == null ? "" : scope.siteId().trim();
            if (scope.level() != ScopeLevel.site || site.isEmpty()) {
                throw new BusinessException("role.scope",
                        "Kiểm soát tài liệu site phải gán ở cấp Site (chọn Site)");
            }
            return new NormalizedScope("site", null, site, null);
        }
        throw new BusinessException("role.scope",
                "Vai trò " + role + " không còn được gán trong mô hình mới (đã thay bằng Kiểm soát tài liệu site)");
    }

    /** Gán vai trò cho người dùng ở một phạm vi. Idempotent. */
    public void grantRole(String userId, RoleCode role, ScopeRef scope, String grantedBy) {
        NormalizedScope s = normalizeScope(role, scope);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("role_code", role.name())
                .addValue("scope_level", s.level())
                .addValue("scope_bu_id", s.bu())
                .addValue("scope_site_id", s.site())
                .addValue("scope_dept_id", s.dept())
                .addValue("granted_by", grantedBy);
        jdbc.update(
                "INSERT INTO user_roles "
                + "(user_id, role_code, scope_level, scope_bu_id, scope_site_id, scope_dept_id, granted_by) "
                + "VALUES "
                + "(:user_id, :role_code, :scope_level, :scope_bu_id, :scope_site_id, :scope_dept_id, :granted_by) "
                + "ON CONFLICT (user_id, role_code, scope_level, scope_bu_id, scope_site_id, scope_dept_id) "
                + "DO UPDATE SET is_active = TRUE",
                p);
    }

    public void revokeRole(String userRoleId) {
        jdbc.update("UPDATE user_roles SET is_active = FALSE WHERE user_role_id = :id",
                new MapSqlParameterSource("id", userRoleId));
    }

    public List<RoleGrant> rolesOfUser(String userId) {
        return jdbc.query(
                "SELECT user_role_id, user_id, role_code, scope_level, "
                + "scope_bu_id, scope_site_id, scope_dept_id, is_active "
                + "FROM user_roles WHERE user_id = :user_id AND is_active ORDER BY role_code",
                new MapSqlParameterSource("user_id", userId),
                (rs, i) -> new RoleGrant(
                        rs.getString("user_role_id"),
                        rs.getString("user_id"),
                        rs.getString("role_code"),
                        rs.getString("scope_level"),
                        rs.getString("scope_bu_id"),
                        rs.getString("scope_site_id"),
                        rs.getString("scope_dept_id"),
                        rs.getBoolean("is_active")));
    }
}
