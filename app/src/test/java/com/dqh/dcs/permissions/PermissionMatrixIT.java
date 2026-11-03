package com.dqh.dcs.permissions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.support.DbSupport;

/**
 * PermissionMatrixIT — ma trận 4 vai + phạm vi site, phần CHẠM DB (port tinh
 * thần test/domain/receipt-permissions + registration-scope). requireDocFlow,
 * canRespondReceipt đúng site, canReportIssue trừ Admin-thuần, quyền đăng ký.
 * Tự SKIP khi không có DB.
 */
class PermissionMatrixIT {

    private PermissionService perms;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        perms = new PermissionService(DbSupport.jdbc());
    }

    @Test
    void authorDangKyDuoc_ReaderKhong() {
        var seed = DbSupport.seed();
        assertTrue(perms.canRegisterDocuments(seed.authorId));
        assertFalse(perms.canRegisterDocuments(seed.readerId));
        assertFalse(perms.canRegisterDocuments(seed.adminId));
    }

    @Test
    void adminCoQuanTri_AuthorKhong() {
        var seed = DbSupport.seed();
        assertTrue(perms.hasSystemAdminAccess(seed.adminId));
        assertFalse(perms.hasSystemAdminAccess(seed.authorId));
    }

    @Test
    void dcSiteChiPhanHoiDungSite() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String site1 = seed.siteIds.get(1);
        assertTrue(perms.canRespondReceipt(seed.dcSite0UserId, site0));
        assertFalse(perms.canRespondReceipt(seed.dcSite0UserId, site1));
        assertTrue(perms.canRespondReceipt(seed.dcSite1UserId, site1));
        // Admin KHÔNG phản hồi receipt.
        assertFalse(perms.canRespondReceipt(seed.adminId, site0));
    }

    @Test
    void docFlowGomAuthorReaderDcSite_truAdminThuan() {
        var seed = DbSupport.seed();
        assertTrue(perms.isDocFlowUser(seed.authorId));
        assertTrue(perms.isDocFlowUser(seed.readerId));
        assertTrue(perms.isDocFlowUser(seed.dcSite0UserId));
        assertFalse(perms.isDocFlowUser(seed.adminId));
    }

    @Test
    void canReportIssueTruAdminThuan() {
        var seed = DbSupport.seed();
        assertFalse(Permissions.canReportIssue(perms.roleCodesOf(seed.adminId)));
        assertTrue(Permissions.canReportIssue(perms.roleCodesOf(seed.readerId)));
        assertTrue(Permissions.canReportIssue(perms.roleCodesOf(seed.authorId)));
        assertTrue(Permissions.canReportIssue(perms.roleCodesOf(seed.dcSite0UserId)));
    }
}
