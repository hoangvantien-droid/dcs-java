package com.dqh.dcs.roles;

/**
 * roles/RoleCode — mô hình 4 vai receipt (port src/domain/roles.ts RoleCode).
 * Vai duyệt-trước cũ (DC_DEPT_HEAD, Site QA/BU/Group owner) đã gỡ.
 */
public enum RoleCode {
    DC_ADMIN,
    DC_AUTHOR,
    DC_SITE_DOC_CONTROL,
    DC_READER;
}
