package com.dqh.dcs.web;

/**
 * web/NavFlags — cờ hiển thị thực đơn theo vai trò (port src/middleware/nav.ts
 * NavFlags). Thực đơn ẩn hẳn mục không có quyền (DCS-7 §1.2).
 */
public record NavFlags(
        boolean canRegister,
        boolean isSiteDocControl,
        boolean canReport,
        boolean isAdmin,
        boolean canSeeMyDocuments,
        boolean isReadOnly,
        boolean inDocFlow,
        int pendingReceipts) {

    /** Khách chưa đăng nhập: không quyền gì. */
    public static NavFlags anonymous() {
        return new NavFlags(false, false, false, false, false, true, false, 0);
    }

    // Getter dạng JavaBean cho Thymeleaf (nav.canRegister, nav.pendingReceipts...).
    public boolean getCanRegister() {
        return canRegister;
    }

    public boolean getIsSiteDocControl() {
        return isSiteDocControl;
    }

    public boolean getCanReport() {
        return canReport;
    }

    public boolean getIsAdmin() {
        return isAdmin;
    }

    public boolean getCanSeeMyDocuments() {
        return canSeeMyDocuments;
    }

    public boolean getIsReadOnly() {
        return isReadOnly;
    }

    public boolean getInDocFlow() {
        return inDocFlow;
    }

    public int getPendingReceipts() {
        return pendingReceipts;
    }
}
