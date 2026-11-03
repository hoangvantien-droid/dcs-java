package com.dqh.dcs.permissions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * PermissionsTest — ma trận 4 vai, phần THUẦN (không DB). Port các hàm thuần
 * trong src/domain/permissions.ts: Reader chỉ-xem, DC Site phản hồi đúng site,
 * canReportIssue trừ Admin-thuần, canExportCatalog = Author/DC Site.
 */
class PermissionsTest {

    @Test
    void readerLaChiXem() {
        assertTrue(Permissions.isReadOnlyUser(Set.of("DC_READER")));
        assertFalse(Permissions.isReadOnlyUser(Set.of("DC_AUTHOR")));
        assertFalse(Permissions.isReadOnlyUser(Set.of("DC_SITE_DOC_CONTROL")));
        assertFalse(Permissions.isReadOnlyUser(Set.of("DC_ADMIN")));
    }

    @Test
    void docFlowGomAuthorSiteReaderTruAdminThuan() {
        assertTrue(Permissions.isDocFlowRoles(Set.of("DC_AUTHOR")));
        assertTrue(Permissions.isDocFlowRoles(Set.of("DC_SITE_DOC_CONTROL")));
        assertTrue(Permissions.isDocFlowRoles(Set.of("DC_READER")));
        assertFalse(Permissions.isDocFlowRoles(Set.of("DC_ADMIN")));
    }

    @Test
    void canRespondReceiptDungSite() {
        Set<String> sites = Set.of("site-A", "site-B");
        assertTrue(Permissions.canRespondReceiptPure(sites, "site-A"));
        assertFalse(Permissions.canRespondReceiptPure(sites, "site-C"));
        assertFalse(Permissions.canRespondReceiptPure(Set.of(), "site-A"));
    }

    @Test
    void canReportIssueTruAdminThuan() {
        assertFalse(Permissions.canReportIssue(Set.of("DC_ADMIN")));
        assertTrue(Permissions.canReportIssue(Set.of("DC_ADMIN", "DC_AUTHOR")));
        assertTrue(Permissions.canReportIssue(Set.of("DC_READER")));
        assertTrue(Permissions.canReportIssue(Set.of("DC_SITE_DOC_CONTROL")));
    }

    @Test
    void canExportCatalogAuthorHoacSiteDocControl() {
        assertTrue(Permissions.canExportCatalog(Set.of("DC_AUTHOR")));
        assertTrue(Permissions.canExportCatalog(Set.of("DC_SITE_DOC_CONTROL")));
        assertFalse(Permissions.canExportCatalog(Set.of("DC_READER")));
        assertFalse(Permissions.canExportCatalog(Set.of("DC_ADMIN")));
    }
}
