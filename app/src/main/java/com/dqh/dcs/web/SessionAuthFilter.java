package com.dqh.dcs.web;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dqh.dcs.config.AppProperties;
import com.dqh.dcs.session.SessionService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/SessionAuthFilter — phân giải người dùng hiện tại từ cookie phiên đã ký
 * (port src/middleware/auth.ts registerAuth, QĐ-E). KHÔNG dùng Spring Security
 * chain (Lệch#1): OncePerRequestFilter tự viết.
 *
 * Cookie dcs_sid mang session_id đã ký HMAC-SHA256 (SESSION_SECRET). Verify →
 * SessionService.resolve → nạp CurrentUser + NavFlags vào request attribute.
 * Cookie httpOnly / sameSite=lax / path=context-path / maxAge 8h.
 */
@Component
@Order(1)
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Tên cookie mang session_id đã ký. */
    public static final String SESSION_COOKIE = "dcs_sid";
    /** Hạn cookie: 8 giờ (khớp SessionService.SESSION_TTL_MS). */
    public static final int COOKIE_MAX_AGE = 8 * 60 * 60;

    private final SessionService sessions;
    private final NavService nav;
    private final CookieSigner signer;
    private final String cookiePath;

    public SessionAuthFilter(SessionService sessions, NavService nav, CookieSigner signer,
            AppProperties props) {
        this.sessions = sessions;
        this.nav = nav;
        this.signer = signer;
        String base = props.getBasePath();
        this.cookiePath = (base == null || base.isEmpty()) ? "/" : base;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        SessionUser user = null;
        String raw = readCookie(request, SESSION_COOKIE);
        if (raw != null) {
            String sessionId = signer.unsign(raw);
            if (sessionId != null && !sessionId.isEmpty()) {
                user = sessions.resolveSession(sessionId);
            }
        }
        request.setAttribute(CurrentUserResolver.ATTR_USER, user);
        request.setAttribute(CurrentUserResolver.ATTR_NAV, nav.buildNav(user));
        chain.doFilter(request, response);
    }

    /** Đặt cookie phiên đã ký (httpOnly, sameSite=lax, path=context-path, 8h). */
    public void setSessionCookie(HttpServletResponse response, String sessionId) {
        String signed = signer.sign(sessionId);
        Cookie cookie = new Cookie(SESSION_COOKIE, signed);
        cookie.setHttpOnly(true);
        cookie.setPath(cookiePath);
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** Xoá cookie phiên (maxAge 0). */
    public void clearSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath(cookiePath);
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** Đọc session_id đã ký từ request (để huỷ phiên khi đăng xuất). */
    public String readSignedSessionId(HttpServletRequest request) {
        String raw = readCookie(request, SESSION_COOKIE);
        return raw == null ? null : signer.unsign(raw);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
