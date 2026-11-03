package com.dqh.dcs.web;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.diff.DiffRow;
import com.dqh.dcs.diff.DiffService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletResponse;

/**
 * web/DiffController — màn so sánh phiên bản (port src/routes/diff.ts, T-059..061).
 */
@Controller
public class DiffController {

    private final Guards guards;
    private final DiffService diff;
    private final CatalogService catalog;

    public DiffController(Guards guards, DiffService diff, CatalogService catalog) {
        this.guards = guards;
        this.diff = diff;
        this.catalog = catalog;
    }

    @GetMapping("/compare")
    public String compare(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            Model model, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        if (to == null || to.isEmpty()) {
            resp.sendError(400, "Thiếu tham số to");
            return null;
        }
        boolean canViewTarget = catalog.canViewVersion(user.userId(), to);
        boolean canViewSource = from == null || from.isEmpty() || catalog.canViewVersion(user.userId(), from);
        if (!canViewTarget || !canViewSource) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        String reliability = diff.diffReliability(from, to);
        List<DiffRow> rows = ("dang_tin".equals(reliability) && from != null && !from.isEmpty())
                ? diff.diffVersions(from, to) : List.of();
        if (from != null && !from.isEmpty()) {
            diff.recordViewedDiff(user.userId(), from, to);
        }
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("reliability", reliability);
        model.addAttribute("rows", rows);
        return "compare";
    }
}
