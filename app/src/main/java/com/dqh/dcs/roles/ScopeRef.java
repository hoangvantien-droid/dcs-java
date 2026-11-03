package com.dqh.dcs.roles;

/**
 * roles/ScopeRef — phạm vi yêu cầu khi gán vai (port src/domain/roles.ts ScopeRef).
 */
public record ScopeRef(ScopeLevel level, String buId, String siteId, String deptId) {

    public static ScopeRef group() {
        return new ScopeRef(ScopeLevel.group, null, null, null);
    }

    public static ScopeRef site(String siteId) {
        return new ScopeRef(ScopeLevel.site, null, siteId, null);
    }
}
