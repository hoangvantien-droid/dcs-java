package com.dqh.dcs.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.dqh.dcs.auth.AuthService;
import com.dqh.dcs.auth.UserRef;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.common.GlobalExceptionHandler;
import com.dqh.dcs.session.SessionService;

/**
 * web/AuthControllerTest — MockMvc slice (FEAT-003 verification).
 *  - GET /login → 200 (render login).
 *  - POST /login sai → render 'login' + giữ status lỗi (401) từ BusinessException.
 */
@WebMvcTest(AuthController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import({ Labels.class, HttpErrors.class, GlobalExceptionHandler.class })
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AuthService auth;
    @MockBean
    SessionService sessions;
    @MockBean
    SessionAuthFilter sessionFilter;
    @MockBean
    CurrentUserResolver currentUser;
    // ViewModelAdvice phụ thuộc — cung cấp dạng mock để context khởi tạo.
    @MockBean
    ViewModelAdvice viewModelAdvice;

    @Test
    void loginGetReturns200() throws Exception {
        when(currentUser.get()).thenReturn(null);
        when(currentUser.nav()).thenReturn(NavFlags.anonymous());
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void loginPostWrongRendersLoginWithErrorStatus() throws Exception {
        when(currentUser.get()).thenReturn(null);
        when(currentUser.nav()).thenReturn(NavFlags.anonymous());
        when(auth.authenticate(anyString(), anyString()))
                .thenThrow(new BusinessException("auth.invalid", "Email hoặc mật khẩu không đúng", 401));
        mvc.perform(post("/login").param("email", "x@y.z").param("password", "bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("login"));
        Mockito.verifyNoInteractions(sessions);
    }
}
