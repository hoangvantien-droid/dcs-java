package com.dqh.dcs.web;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.issuereport.IssueReportService;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/IssueReportController — report bất thường (Reader) + thẩm định (DC Site)
 * (port src/routes/issue-reports.ts).
 */
@Controller
public class IssueReportController {

    private final Guards guards;
    private final IssueReportService issueReports;
    private final PermissionService permissions;
    private final HttpErrors httpErrors;

    public IssueReportController(Guards guards, IssueReportService issueReports,
            PermissionService permissions, HttpErrors httpErrors) {
        this.guards = guards;
        this.issueReports = issueReports;
        this.permissions = permissions;
        this.httpErrors = httpErrors;
    }

    /** Reader gửi report bất thường về một phiên bản. */
    @PostMapping("/documents/{id}/report")
    public void report(@PathVariable String id, @RequestParam(required = false) String note,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            issueReports.createIssueReport(id, user.userId(), note == null ? "" : note);
            httpErrors.sendOk(req, resp, "/documents/" + id);
        } catch (BusinessException err) {
            if (!httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                throw err;
            }
        }
    }

    /** Hộp report của site (DC Site). */
    @GetMapping("/issue-reports")
    public String issueReports(Model model) {
        SessionUser user = guards.requireUser();
        List<String> siteIds = List.copyOf(permissions.siteIdsWithDocControl(user.userId()));
        if (siteIds.isEmpty()) {
            model.addAttribute("reports", List.of());
            model.addAttribute("notControl", true);
            return "issue-reports";
        }
        model.addAttribute("reports", issueReports.pendingReportsForSites(siteIds));
        model.addAttribute("notControl", false);
        return "issue-reports";
    }

    /** Chuyển report thành từ chối receipt. */
    @PostMapping("/issue-reports/{id}/convert")
    public void convert(@PathVariable String id,
            @RequestParam(name = "reject_type", required = false) String rejectType,
            @RequestParam(required = false) String note, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        if (!"sai_dang_ky".equals(rejectType) && !"sai_noi_dung".equals(rejectType)) {
            resp.sendError(400, "Loại từ chối không hợp lệ");
            return;
        }
        try {
            issueReports.convertReportToRejection(id, user.userId(), rejectType, note == null ? "" : note);
            httpErrors.sendOk(req, resp, "/issue-reports");
        } catch (BusinessException err) {
            if (!httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                throw err;
            }
        }
    }

    /** Bác bỏ report. */
    @PostMapping("/issue-reports/{id}/dismiss")
    public void dismiss(@PathVariable String id,
            @RequestParam(name = "review_note", required = false) String reviewNote,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            issueReports.dismissReport(id, user.userId(), reviewNote == null ? "" : reviewNote);
            httpErrors.sendOk(req, resp, "/issue-reports");
        } catch (BusinessException err) {
            if (!httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                throw err;
            }
        }
    }
}
