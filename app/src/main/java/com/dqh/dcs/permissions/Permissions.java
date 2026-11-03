package com.dqh.dcs.permissions;

import java.util.List;
import java.util.Set;

import com.dqh.dcs.lifecycle.LifecycleStatus;

/**
 * permissions/Permissions — các hàm kiểm quyền THUẦN (không chạm DB) của mô hình
 * 4 vai receipt (port các hàm thuần trong src/domain/permissions.ts).
 *
 * Vai: Admin (quản trị, ngoài luồng) / Author (biên soạn) / DC_SITE_DOC_CONTROL
 * (nhận & phản hồi theo site) / Reader (đọc theo scope). Kiểm quyền đặt ở tầng
 * domain, không tin controller (DCS-8 §4.3).
 */
public final class Permissions {

    private Permissions() {
    }

    /** Người chỉ-xem (Reader): không đăng ký, không nhận-phản hồi, không quản trị. */
    public static boolean isReadOnlyUser(Set<String> roleCodes) {
        boolean canRegister = roleCodes.contains("DC_AUTHOR");
        boolean siteDocControl = roleCodes.contains("DC_SITE_DOC_CONTROL");
        boolean isAdmin = roleCodes.contains("DC_ADMIN");
        return !canRegister && !siteDocControl && !isAdmin;
    }

    /**
     * Tập trạng thái Reader được phép thấy. Reader -> chỉ tài liệu hiện hành;
     * vai nghiệp vụ -> null (không giới hạn, xem cả lịch sử).
     */
    public static List<String> allowedStatusesForViewer(Set<String> roleCodes) {
        return isReadOnlyUser(roleCodes) ? LifecycleStatus.CURRENT_VIEW_STATUSES : null;
    }

    /** Có phải vai Kiểm soát tài liệu site không (bất kể site nào). */
    public static boolean isSiteDocControl(Set<String> roleCodes) {
        return roleCodes.contains("DC_SITE_DOC_CONTROL");
    }

    /** Được xuất danh mục Excel = vai Author HOẶC SiteDocControl. */
    public static boolean canExportCatalog(Set<String> roleCodes) {
        return roleCodes.contains("DC_AUTHOR") || isSiteDocControl(roleCodes);
    }

    /** Tham gia luồng tài liệu = Author / DC Site / Reader. Admin-thuần đứng ngoài. */
    public static boolean isDocFlowRoles(Set<String> roleCodes) {
        return roleCodes.contains("DC_AUTHOR")
                || roleCodes.contains("DC_SITE_DOC_CONTROL")
                || roleCodes.contains("DC_READER");
    }

    /** Quyền phản hồi receipt của MỘT site — thuần. DC Site đúng site; Admin KHÔNG. */
    public static boolean canRespondReceiptPure(Set<String> docControlSiteIds, String receiptSiteId) {
        return docControlSiteIds.contains(receiptSiteId);
    }

    /** Quyền gửi report bất thường: mọi vai đọc được, trừ Admin-thuần. */
    public static boolean canReportIssue(Set<String> roleCodes) {
        boolean onlyAdmin = roleCodes.contains("DC_ADMIN") && roleCodes.size() == 1;
        return !onlyAdmin;
    }
}
