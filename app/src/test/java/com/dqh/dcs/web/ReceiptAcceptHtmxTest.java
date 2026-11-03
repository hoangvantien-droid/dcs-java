package com.dqh.dcs.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.GlobalExceptionHandler;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/ReceiptAcceptHtmxTest — MockMvc slice (FEAT-003 verification).
 * POST /site-receipts/:id/accept với header HX-Request → 204 + HX-Redirect
 * (sendOk của HttpErrors: htmx thành công điều hướng qua HX-Redirect).
 */
@WebMvcTest(ReceiptController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import({ Guards.class, Labels.class, HttpErrors.class, GlobalExceptionHandler.class })
class ReceiptAcceptHtmxTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    CurrentUserResolver currentUser;
    @MockBean
    PermissionService permissions;
    @MockBean
    ReceiptService receipts;
    @MockBean
    CatalogService catalog;
    @MockBean
    ViewModelAdvice viewModelAdvice;
    @MockBean
    SessionAuthFilter sessionAuthFilter;

    @Test
    void acceptViaHtmxReturns204WithHxRedirect() throws Exception {
        SessionUser user = new SessionUser("22222222-2222-2222-2222-222222222222", "dc@x", "DC Site");
        when(currentUser.get()).thenReturn(user);
        when(currentUser.nav()).thenReturn(NavFlags.anonymous());
        // acceptReceipt là void — mặc định mock không ném lỗi = thành công.

        mvc.perform(post("/site-receipts/{id}/accept", "33333333-3333-3333-3333-333333333333")
                        .header("HX-Request", "true"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Redirect", "/site-receipts"));
    }
}
