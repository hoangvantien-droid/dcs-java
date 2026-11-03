package com.dqh.dcs.web;

import org.springframework.stereotype.Component;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/Guards — cổng kiểm quyền ở tầng web (port requireUser / requireDocFlow /
 * requireSystemAdmin của src/middleware/auth.ts + routes). Phòng thủ sâu: ẩn
 * menu chưa đủ, URL trực tiếp cũng phải bị chặn. Kiểm quyền THỰC nằm ở domain.
 */
@Component
public class Guards {

    private final CurrentUserResolver currentUser;
    private final PermissionService permissions;

    public Guards(CurrentUserResolver currentUser, PermissionService permissions) {
        this.currentUser = currentUser;
        this.permissions = permissions;
    }

    /** Bắt buộc đã đăng nhập; chưa thì chuyển hướng /login (302). */
    public SessionUser requireUser() {
        SessionUser user = currentUser.get();
        if (user == null) {
            throw new RedirectToLoginException();
        }
        return user;
    }

    /** Chặn Admin-THUẦN (đứng ngoài luồng tài liệu) — trả 403. */
    public SessionUser requireDocFlow() {
        SessionUser user = requireUser();
        if (!permissions.isDocFlowUser(user.userId())) {
            throw new BusinessException("docflow.forbidden",
                    "Admin quản trị không truy cập trang nghiệp vụ tài liệu. Dùng tài khoản có vai Biên "
                    + "soạn / Kiểm soát tài liệu site / Người đọc.",
                    403);
        }
        return user;
    }

    /** Chặn khi không có quyền quản trị hệ thống (DC_ADMIN) — trả 403. */
    public SessionUser requireSystemAdmin() {
        SessionUser user = requireUser();
        if (!permissions.hasSystemAdminAccess(user.userId())) {
            throw new BusinessException("admin.forbidden", "Không đủ quyền quản trị hệ thống", 403);
        }
        return user;
    }

    public CurrentUserResolver currentUser() {
        return currentUser;
    }

    public PermissionService permissions() {
        return permissions;
    }
}
