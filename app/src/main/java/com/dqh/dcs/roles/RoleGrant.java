package com.dqh.dcs.roles;

/**
 * roles/RoleGrant — một dòng gán vai trò đang active (port src/domain/roles.ts RoleGrant).
 */
public record RoleGrant(
        String userRoleId,
        String userId,
        String roleCode,
        String scopeLevel,
        String scopeBuId,
        String scopeSiteId,
        String scopeDeptId,
        boolean active) {
}
