package com.dqh.dcs.common;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import com.dqh.dcs.web.HttpErrors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * common/GlobalExceptionHandler — ánh xạ BusinessException ra thông báo thân
 * thiện (port lib/http-errors.ts + errors.ts, QĐ-G).
 *
 *  - htmx (HX-Request): fragment toast + giữ httpStatus (HttpErrors).
 *  - thường: view error.html + giữ status.
 *
 * Lỗi KHÔNG phải BusinessException (gồm lỗi ràng buộc DB lớp 23) KHÔNG đi qua
 * đây — để nó ném ra, trả 500 thật (DCS-9 bẫy số 1: KHÔNG nuốt).
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private final HttpErrors httpErrors;

    public GlobalExceptionHandler(HttpErrors httpErrors) {
        this.httpErrors = httpErrors;
    }

    @ExceptionHandler(com.dqh.dcs.web.RedirectToLoginException.class)
    public void handleRedirectToLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        resp.sendRedirect(ctx + "/login");
    }

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusiness(BusinessException ex, HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        if (httpErrors.sendBusinessErrorHtmx(req, resp, ex)) {
            return null;
        }
        // Non-htmx: trang error với layout + alert-banner, giữ status.
        resp.setStatus(ex.getHttpStatus());
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("message", ex.getMessage());
        mav.setStatus(HttpStatus.valueOf(ex.getHttpStatus()));
        return mav;
    }
}
