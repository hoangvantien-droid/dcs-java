package com.dqh.dcs.permissions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * permissions/PermissionService — truy vấn quyền từ user_roles (port các hàm
 * async trong src/domain/permissions.ts). Phần thuần tách sang Permissions.
 */
@Service
public class PermissionService {

    private final NamedParameterJdbcTemplate jdbc;

    public PermissionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean canRegisterDocuments(String userId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles "
                + "WHERE user_id = :user_id AND is_active AND role_code = 'DC_AUTHOR') AS ok",
                new MapSqlParameterSource("user_id", userId), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    public boolean hasSystemAdminAccess(String userId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles "
                + "WHERE user_id = :user_id AND role_code = 'DC_ADMIN' AND is_active) AS ok",
                new MapSqlParameterSource("user_id", userId), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    /** Tập mã vai trò đang active của người dùng (không xét phạm vi) — cho menu. */
    public Set<String> roleCodesOf(String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT DISTINCT role_code FROM user_roles WHERE user_id = :user_id AND is_active",
                new MapSqlParameterSource("user_id", userId), String.class);
        return new HashSet<>(rows);
    }

    public boolean isDocFlowUser(String userId) {
        return Permissions.isDocFlowRoles(roleCodesOf(userId));
    }

    /** Tập site_id mà người dùng giữ vai DC_SITE_DOC_CONTROL (đang active). */
    public Set<String> siteIdsWithDocControl(String userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT scope_site_id FROM user_roles "
                + "WHERE user_id = :user_id AND is_active "
                + "AND role_code = 'DC_SITE_DOC_CONTROL' AND scope_site_id IS NOT NULL",
                new MapSqlParameterSource("user_id", userId), String.class);
        return new HashSet<>(rows);
    }

    /** Quyền phản hồi receipt (async) — ghép truy vấn site với hàm thuần. */
    public boolean canRespondReceipt(String userId, String receiptSiteId) {
        return Permissions.canRespondReceiptPure(siteIdsWithDocControl(userId), receiptSiteId);
    }
}
