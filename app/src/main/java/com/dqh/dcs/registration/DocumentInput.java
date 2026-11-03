package com.dqh.dcs.registration;

import com.dqh.dcs.roles.ScopeLevel;

/**
 * registration/DocumentInput — dữ liệu tạo bản ghi documents
 * (port src/domain/registration.ts DocumentInput).
 */
public record DocumentInput(
        String docId,
        String docType,
        String origin,
        ScopeLevel scopeLevel,
        String scopeBuId,
        String scopeSiteId,
        String scopeDeptId) {
}
