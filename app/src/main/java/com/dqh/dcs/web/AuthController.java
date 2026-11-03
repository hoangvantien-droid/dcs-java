package com.dqh.dcs.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.auth.AuthService;
import com.dqh.dcs.auth.UserRef;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.session.SessionService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * web/AuthController — đăng nhập, đăng xuất (port src/routes/auth.ts). Controller
 * MỎNG: gọi domain. Đặt cookie dcs_sid qua SessionAuthFilter.
 */
@Controller
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;
    private final SessionAuthFilter sessionFilter;
    private final CurrentUserResolver currentUser;

    public AuthController(AuthService auth, SessionService sessions, SessionAuthFilter sessionFilter,
            CurrentUserResolver currentUser) {
        this.auth = auth;
        this.sessions = sessions;
        this.sessionFilter = sessionFilter;
        this.currentUser = currentUser;
    }

    @GetMapping("/login")
    public String loginForm(Model model) {
        if (currentUser.get() != null) {
            return "redirect:/";
        }
        model.addAttribute("error", null);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(required = false) String email,
            @RequestParam(required = false) String password, Model model, HttpServletResponse resp) {
        String e = email == null ? "" : email.trim();
        String p = password == null ? "" : password;
        try {
            UserRef user = auth.authenticate(e, p);
            String sessionId = sessions.createSession(user.userId());
            sessionFilter.setSessionCookie(resp, sessionId);
            return "redirect:/";
        } catch (BusinessException err) {
            resp.setStatus(err.getHttpStatus());
            model.addAttribute("error", err.getMessage());
            return "login";
        }
    }

    @GetMapping("/logout")
    public String logout(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse resp) {
        String sessionId = sessionFilter.readSignedSessionId(req);
        if (sessionId != null && !sessionId.isEmpty()) {
            sessions.destroySession(sessionId);
        }
        sessionFilter.clearSessionCookie(resp);
        return "redirect:/login";
    }
}
