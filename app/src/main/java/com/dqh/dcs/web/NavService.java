package com.dqh.dcs.web;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.permissions.Permissions;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/NavService — dựng cờ hiển thị thực đơn theo vai trò (port
 * src/middleware/nav.ts buildNav). Cờ dựa trên vai trò đang active.
 */
@Service
public class NavService {

    private final PermissionService permissions;
    private final ReceiptService receipts;

    public NavService(PermissionService permissions, ReceiptService receipts) {
        this.permissions = permissions;
        this.receipts = receipts;
    }

    public NavFlags buildNav(SessionUser user) {
        if (user == null) {
            return NavFlags.anonymous();
        }
        Set<String> roles = permissions.roleCodesOf(user.userId());
        boolean canRegister = roles.contains("DC_AUTHOR");
        boolean siteDocControl = Permissions.isSiteDocControl(roles);
        boolean isAdmin = roles.contains("DC_ADMIN");
        boolean reader = roles.contains("DC_READER");
        int pendingReceipts = 0;
        if (siteDocControl) {
            List<String> siteIds = List.copyOf(permissions.siteIdsWithDocControl(user.userId()));
            pendingReceipts = receipts.pendingReceiptCountForSites(siteIds);
        }
        boolean inDocFlow = canRegister || siteDocControl || reader;
        return new NavFlags(
                canRegister,
                siteDocControl,
                Permissions.canReportIssue(roles),
                isAdmin,
                canRegister,
                Permissions.isReadOnlyUser(roles),
                inDocFlow,
                pendingReceipts);
    }
}
