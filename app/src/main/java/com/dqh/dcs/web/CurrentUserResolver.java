package com.dqh.dcs.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * web/CurrentUserResolver — ĐƯỜNG DUY NHẤT lấy người dùng hiện tại (port
 * src/middleware/auth.ts getCurrentUser, DCS-8 §4.2).
 *
 * Không chỗ nào được đọc cookie phiên trực tiếp. SessionAuthFilter đặt
 * currentUser + nav vào request attribute; mọi nơi khác đọc qua đây. Khi L1 lên
 * và đổi sang OIDC chỉ sửa filter + resolver.
 */
@Component
public class CurrentUserResolver {

    /** Khoá request attribute cho người dùng hiện tại. */
    public static final String ATTR_USER = "dcs.currentUser";
    /** Khoá request attribute cho cờ thực đơn. */
    public static final String ATTR_NAV = "dcs.nav";

    /** Người dùng hiện tại của request đang xử lý, hoặc null nếu chưa đăng nhập. */
    public SessionUser get() {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return null;
        }
        Object u = req.getAttribute(ATTR_USER);
        return u instanceof SessionUser su ? su : null;
    }

    /** Cờ thực đơn của request đang xử lý. */
    public NavFlags nav() {
        HttpServletRequest req = currentRequest();
        if (req != null) {
            Object n = req.getAttribute(ATTR_NAV);
            if (n instanceof NavFlags nf) {
                return nf;
            }
        }
        return NavFlags.anonymous();
    }

    static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
