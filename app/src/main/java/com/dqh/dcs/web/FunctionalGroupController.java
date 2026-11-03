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
import com.dqh.dcs.functionalgroups.FunctionalGroupService;
import com.dqh.dcs.session.SessionUser;
import com.dqh.dcs.users.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/FunctionalGroupController — nhóm chức năng (port
 * src/routes/functional-groups.ts, T-067, T-068). requireSystemAdmin.
 */
@Controller
public class FunctionalGroupController {

    private final Guards guards;
    private final FunctionalGroupService groups;
    private final UserService users;
    private final HttpErrors httpErrors;

    public FunctionalGroupController(Guards guards, FunctionalGroupService groups, UserService users,
            HttpErrors httpErrors) {
        this.guards = guards;
        this.groups = groups;
        this.users = users;
        this.httpErrors = httpErrors;
    }

    @GetMapping("/functional-groups")
    public String list(Model model) {
        guards.requireSystemAdmin();
        List<FunctionalGroupService.FunctionalGroup> gs = groups.listGroups();
        Map<String, List<FunctionalGroupService.Member>> membersByGroup = new LinkedHashMap<>();
        for (FunctionalGroupService.FunctionalGroup g : gs) {
            membersByGroup.put(g.groupId(), groups.membersOf(g.groupId()));
        }
        model.addAttribute("groups", gs);
        model.addAttribute("users", users.listUsers());
        model.addAttribute("stale", groups.staleMembers());
        model.addAttribute("membersByGroup", membersByGroup);
        model.addAttribute("error", null);
        return "functional-groups";
    }

    @PostMapping("/functional-groups")
    public Object create(@RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(name = "owner_user_id", required = false) String ownerUserId,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        guards.requireSystemAdmin();
        try {
            groups.createGroup(new FunctionalGroupService.CreateGroupInput(
                    code == null ? "" : code.trim(), name == null ? "" : name.trim(),
                    ownerUserId == null ? "" : ownerUserId));
            return "redirect:/functional-groups";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    @PostMapping("/functional-groups/members/add")
    public String addMember(@RequestParam(name = "group_id", required = false) String groupId,
            @RequestParam(name = "user_id", required = false) String userId) {
        SessionUser user = guards.requireSystemAdmin();
        groups.addMember(groupId == null ? "" : groupId, userId == null ? "" : userId, user.userId());
        return "redirect:/functional-groups";
    }

    @PostMapping("/functional-groups/members/remove")
    public String removeMember(@RequestParam(name = "group_id", required = false) String groupId,
            @RequestParam(name = "user_id", required = false) String userId) {
        guards.requireSystemAdmin();
        groups.removeMember(groupId == null ? "" : groupId, userId == null ? "" : userId);
        return "redirect:/functional-groups";
    }
}
