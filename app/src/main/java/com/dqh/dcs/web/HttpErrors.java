package com.dqh.dcs.web;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.dqh.dcs.common.BusinessException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/HttpErrors — hiển thị BusinessException thân thiện (port
 * src/lib/http-errors.ts, QĐ-G).
 *
 *  - Request htmx (header HX-Request): trả FRAGMENT toast (tone error) + status
 *    lỗi → htmx chèn vào #toast-zone, người dùng KHÔNG rời trang.
 *  - Request thường: trang error (do GlobalExceptionHandler render), giữ status.
 *  - Lỗi KHÔNG phải BusinessException KHÔNG đi qua đây → 500 thật.
 */
@Component
public class HttpErrors {

    /** Request có phải do htmx gửi không (htmx đặt header HX-Request: true). */
    public static boolean isHtmx(HttpServletRequest req) {
        return "true".equals(req.getHeader("HX-Request"));
    }

    /** Escape tối thiểu cho nội dung chèn vào HTML fragment. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Trả BusinessException dưới dạng cảnh báo tại chỗ CHO htmx (fragment toast).
     * Trả true nếu đã xử lý (htmx); false nếu non-htmx (để bên gọi render trang
     * error.html giữ status).
     */
    public boolean sendBusinessErrorHtmx(HttpServletRequest req, HttpServletResponse resp,
            BusinessException err) throws IOException {
        resp.setStatus(err.getHttpStatus());
        if (!isHtmx(req)) {
            return false;
        }
        resp.setContentType("text/html; charset=utf-8");
        String html = "<div class=\"toast toast--error\" role=\"alert\" data-toast><strong>Cảnh báo:</strong> "
                + esc(err.getMessage()) + "</div>"
                + "<script>(function(){var e=document.querySelectorAll(\"[data-toast]\");"
                + "var t=e[e.length-1];if(!t)return;setTimeout(function(){t.style.display=\"none\";"
                + "if(t.parentNode)t.parentNode.removeChild(t);},5000);})();</script>";
        resp.getWriter().write(html);
        return true;
    }

    /**
     * Báo thành công cho htmx: điều hướng qua header HX-Redirect (204). Non-htmx:
     * redirect thường (302). Giữ luồng thành công như cũ (sendOk).
     */
    public void sendOk(HttpServletRequest req, HttpServletResponse resp, String redirectTo) throws IOException {
        if (isHtmx(req)) {
            resp.setHeader("HX-Redirect", withBase(req, redirectTo));
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        resp.sendRedirect(withBase(req, redirectTo));
    }

    /** Thêm context-path vào đường dẫn nội bộ bắt đầu bằng '/'. */
    private static String withBase(HttpServletRequest req, String path) {
        String ctx = req.getContextPath();
        if (ctx == null || ctx.isEmpty() || !path.startsWith("/")) {
            return path;
        }
        if (path.equals(ctx) || path.startsWith(ctx + "/")) {
            return path;
        }
        return ctx + path;
    }
}
