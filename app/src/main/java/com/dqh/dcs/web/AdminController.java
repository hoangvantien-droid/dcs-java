package com.dqh.dcs.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.org.OrgService;
import com.dqh.dcs.roles.RoleCode;
import com.dqh.dcs.roles.RoleGrant;
import com.dqh.dcs.roles.RoleService;
import com.dqh.dcs.roles.ScopeLevel;
import com.dqh.dcs.roles.ScopeRef;
import com.dqh.dcs.session.SessionUser;
import com.dqh.dcs.users.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/AdminController — quản trị tổ chức, người dùng, vai trò (port
 * src/routes/admin.ts). Màn hình CÓ HẠN SỬ DỤNG, gỡ khi L1 lên (DCS-7 §7.4).
 * Mọi route requireSystemAdmin.
 */
@Controller
public class AdminController {

    private final Guards guards;
    private final CurrentUserResolver currentUser;
    private final OrgService org;
    private final UserService users;
    private final RoleService roles;
    private final HttpErrors httpErrors;

    public AdminController(Guards guards, CurrentUserResolver currentUser, OrgService org, UserService users,
            RoleService roles, HttpErrors httpErrors) {
        this.guards = guards;
        this.currentUser = currentUser;
        this.org = org;
        this.users = users;
        this.roles = roles;
        this.httpErrors = httpErrors;
    }

    private static String reqField(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new BusinessException("form.missing", "Thiếu trường bắt buộc: " + name);
        }
        return v;
    }

    private static String t(String s) {
        return s == null ? "" : s.trim();
    }

    // ---- Tổ chức (T-013) ----
    @GetMapping("/admin/org")
    public String org(Model model) {
        guards.requireSystemAdmin();
        model.addAttribute("user", currentUser.get());
        model.addAttribute("bus", org.listBu());
        model.addAttribute("sites", org.listSites());
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("error", null);
        return "admin/org";
    }

    @PostMapping("/admin/org/bu")
    public String createBu(@RequestParam(required = false) String code,
            @RequestParam(required = false) String name) {
        guards.requireSystemAdmin();
        org.createBu(t(code), t(name));
        return "redirect:/admin/org";
    }

    @PostMapping("/admin/org/site")
    public String createSite(@RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(name = "bu_id", required = false) String buId) {
        guards.requireSystemAdmin();
        org.createSite(t(code), t(name), reqField(buId, "bu_id"));
        return "redirect:/admin/org";
    }

    @PostMapping("/admin/org/site-bu")
    public String addSiteBu(@RequestParam(name = "site_id", required = false) String siteId,
            @RequestParam(name = "bu_id", required = false) String buId) {
        guards.requireSystemAdmin();
        org.addSiteBu(reqField(siteId, "site_id"), reqField(buId, "bu_id"));
        return "redirect:/admin/org";
    }

    @PostMapping("/admin/org/dept")
    public String createDept(@RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(name = "site_id", required = false) String siteId) {
        guards.requireSystemAdmin();
        org.createDepartment(t(code), t(name), reqField(siteId, "site_id"));
        return "redirect:/admin/org";
    }

    // ---- Người dùng (T-014) ----
    @GetMapping("/admin/users")
    public String users(Model model) {
        guards.requireSystemAdmin();
        List<UserService.UserRow> all = users.listUsers();
        Map<String, List<String>> deptsByUser = new LinkedHashMap<>();
        for (UserService.UserRow u : all) {
            deptsByUser.put(u.userId(), users.departmentsOfUser(u.userId()));
        }
        model.addAttribute("user", currentUser.get());
        model.addAttribute("users", all);
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("deptsByUser", deptsByUser);
        model.addAttribute("error", null);
        return "admin/users";
    }

    @PostMapping("/admin/users")
    public String createUser(@RequestParam(required = false) String email,
            @RequestParam(name = "full_name", required = false) String fullName,
            @RequestParam(required = false) String password, Model model) {
        guards.requireSystemAdmin();
        try {
            users.createUser(new UserService.CreateUserInput(t(email), t(fullName),
                    password == null ? "" : password));
            return "redirect:/admin/users";
        } catch (BusinessException err) {
            model.addAttribute("user", currentUser.get());
            model.addAttribute("users", users.listUsers());
            model.addAttribute("depts", org.listDepartments());
            model.addAttribute("deptsByUser", Map.of());
            model.addAttribute("error", err.getMessage());
            return "admin/users";
        }
    }

    @PostMapping("/admin/users/assign-dept")
    public String assignDept(@RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "dept_id", required = false) String deptId) {
        guards.requireSystemAdmin();
        users.assignToDepartment(reqField(userId, "user_id"), reqField(deptId, "dept_id"));
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/remove-dept")
    public String removeDept(@RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "dept_id", required = false) String deptId) {
        guards.requireSystemAdmin();
        users.removeFromDepartment(reqField(userId, "user_id"), reqField(deptId, "dept_id"));
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/active")
    public String setActive(@RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(required = false) String active) {
        guards.requireSystemAdmin();
        users.setUserActive(reqField(userId, "user_id"), "true".equals(active));
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/delete")
    public Object deleteUser(@RequestParam(name = "user_id", required = false) String userId,
            HttpServletResponse resp) throws IOException {
        SessionUser me = guards.requireSystemAdmin();
        String id = reqField(userId, "user_id");
        if (id.equals(me.userId())) {
            resp.sendError(422, "Không thể tự xoá tài khoản đang đăng nhập");
            return null;
        }
        users.setUserActive(id, false);
        return "redirect:/admin/users";
    }

    // ---- Vai trò theo phạm vi (T-015) ----
    @GetMapping("/admin/roles")
    public String rolesPage(Model model) {
        guards.requireSystemAdmin();
        List<UserService.UserRow> all = users.listUsers();
        Map<String, List<RoleGrant>> rolesByUser = new LinkedHashMap<>();
        for (UserService.UserRow u : all) {
            rolesByUser.put(u.userId(), roles.rolesOfUser(u.userId()));
        }
        model.addAttribute("user", currentUser.get());
        model.addAttribute("users", all);
        model.addAttribute("bus", org.listBu());
        model.addAttribute("sites", org.listSites());
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("rolesByUser", rolesByUser);
        model.addAttribute("error", null);
        return "admin/roles";
    }

    @PostMapping("/admin/roles")
    public Object grantRole(@RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "role_code", required = false) String roleCode,
            @RequestParam(name = "scope_level", required = false) String scopeLevel,
            @RequestParam(name = "scope_bu_id", required = false) String scopeBuId,
            @RequestParam(name = "scope_site_id", required = false) String scopeSiteId,
            @RequestParam(name = "scope_dept_id", required = false) String scopeDeptId,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser me = guards.requireSystemAdmin();
        try {
            RoleCode role = RoleCode.valueOf(reqField(roleCode, "role_code"));
            ScopeLevel level = ScopeLevel.valueOf(scopeLevel == null || scopeLevel.isEmpty() ? "group" : scopeLevel);
            ScopeRef scope = new ScopeRef(level, emptyToNull(scopeBuId), emptyToNull(scopeSiteId),
                    emptyToNull(scopeDeptId));
            roles.grantRole(reqField(userId, "user_id"), role, scope, me.userId());
            return "redirect:/admin/roles";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
