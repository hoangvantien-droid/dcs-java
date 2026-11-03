package com.dqh.dcs.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.dqh.dcs.acknowledgement.AcknowledgementService;
import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.GlobalExceptionHandler;
import com.dqh.dcs.org.OrgService;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.printing.PrintingService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/DocFlowGuardTest — MockMvc slice (FEAT-003 verification).
 *  - Admin-thuần mở /documents → 403 (requireDocFlow chặn).
 *  - Chưa đăng nhập → 302 /login (requireUser redirect).
 */
@WebMvcTest(CatalogController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import({ Guards.class, Labels.class, HttpErrors.class, GlobalExceptionHandler.class, ViewModelAdvice.class })
class DocFlowGuardTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    CurrentUserResolver currentUser;
    @MockBean
    PermissionService permissions;
    @MockBean
    CatalogService catalog;
    @MockBean
    OrgService org;
    @MockBean
    ReceiptService receipts;
    @MockBean
    AcknowledgementService acknowledgements;
    @MockBean
    PrintingService printing;
    @MockBean
    SessionAuthFilter sessionAuthFilter;
    @MockBean
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @MockBean
    com.dqh.dcs.config.AppProperties appProperties;

    @Test
    void adminOnlyUserGetsForbiddenOnDocuments() throws Exception {
        SessionUser admin = new SessionUser("11111111-1111-1111-1111-111111111111", "admin@x", "Admin");
        when(currentUser.get()).thenReturn(admin);
        when(currentUser.nav()).thenReturn(NavFlags.anonymous());
        when(permissions.isDocFlowUser(admin.userId())).thenReturn(false);
        mvc.perform(get("/documents")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRedirectedToLogin() throws Exception {
        when(currentUser.get()).thenReturn(null);
        when(currentUser.nav()).thenReturn(NavFlags.anonymous());
        mvc.perform(get("/documents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
