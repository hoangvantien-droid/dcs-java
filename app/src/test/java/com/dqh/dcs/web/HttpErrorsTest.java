package com.dqh.dcs.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.dqh.dcs.common.BusinessException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * HttpErrorsTest — hiển thị BusinessException thân thiện (thuần, mock req/resp).
 * Port test/lib/http-errors.test.ts + QĐ-G:
 *   - htmx -> fragment toast--error + httpStatus lỗi, escape HTML;
 *   - non-htmx -> trả false (controller render trang error, giữ status);
 *   - sendOk htmx -> HX-Redirect + 204; non-htmx -> redirect thường.
 * Không chạm mạng, không cần DB.
 */
class HttpErrorsTest {

    private final HttpErrors http = new HttpErrors();

    private static MockHttpServletRequest req(boolean htmx) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (htmx) {
            r.addHeader("HX-Request", "true");
        }
        return r;
    }

    @Test
    void isHtmxTheoHeader() {
        assertTrue(HttpErrors.isHtmx(req(true)));
        assertFalse(HttpErrors.isHtmx(req(false)));
    }

    @Test
    void htmxTraFragmentToastErrorVaHttpStatus() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        BusinessException err = new BusinessException("x.y", "Chỉ in được bản đang hiệu lực", 422);
        boolean handled = http.sendBusinessErrorHtmx(req(true), resp, err);
        assertTrue(handled);
        assertEquals(422, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("toast--error"));
        assertTrue(resp.getContentAsString().contains("Chỉ in được bản đang hiệu lực"));
    }

    @Test
    void nonHtmxTraFalseGiuStatus() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        BusinessException err = new BusinessException("x.y", "Sai nghiệp vụ", 403);
        boolean handled = http.sendBusinessErrorHtmx(req(false), resp, err);
        assertFalse(handled);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void escapeHtmlTrongMessageHtmx() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        BusinessException err = new BusinessException("x", "<script>x</script>", 422);
        http.sendBusinessErrorHtmx(req(true), resp, err);
        String body = resp.getContentAsString();
        assertFalse(body.contains("<script>x</script>"));
        assertTrue(body.contains("&lt;script&gt;"));
    }

    @Test
    void sendOkHtmxHxRedirectVa204() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        http.sendOk(req(true), resp, "/my-documents");
        assertEquals("/my-documents", resp.getHeader("HX-Redirect"));
        assertEquals(HttpServletResponse.SC_NO_CONTENT, resp.getStatus());
    }

    @Test
    void sendOkNonHtmxRedirectThuong() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        http.sendOk(req(false), resp, "/my-documents");
        assertEquals("/my-documents", resp.getRedirectedUrl());
    }
}
