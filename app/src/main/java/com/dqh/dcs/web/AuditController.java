package com.dqh.dcs.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.audit.AuditQueryService;
import com.dqh.dcs.common.BusinessException;

/**
 * web/AuditController — màn tra cứu ghi vết (port src/routes/audit.ts, REQ-G).
 * Chỉ Admin xem.
 */
@Controller
public class AuditController {

    private final Guards guards;
    private final CurrentUserResolver currentUser;
    private final AuditQueryService auditQuery;

    public AuditController(Guards guards, CurrentUserResolver currentUser, AuditQueryService auditQuery) {
        this.guards = guards;
        this.currentUser = currentUser;
        this.auditQuery = auditQuery;
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(name = "object_id", required = false) String objectId,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            Model model) {
        guards.requireUser();
        if (!currentUser.nav().isAdmin()) {
            throw new BusinessException("audit.forbidden", "Không đủ thẩm quyền xem ghi vết", 403);
        }
        AuditQueryService.AuditFilter filter = new AuditQueryService.AuditFilter(
                emptyToNull(objectId), emptyToNull(eventType), emptyToNull(from), emptyToNull(to), null);
        Map<String, String> filterView = new LinkedHashMap<>();
        filterView.put("object_id", objectId == null ? "" : objectId);
        filterView.put("event_type", eventType == null ? "" : eventType);
        filterView.put("from", from == null ? "" : from);
        filterView.put("to", to == null ? "" : to);
        model.addAttribute("rows", auditQuery.queryAudit(filter));
        model.addAttribute("filter", filterView);
        model.addAttribute("eventTypes", AuditQueryService.EVENT_TYPES);
        return "audit";
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
