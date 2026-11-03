package com.dqh.dcs.web;

import java.io.IOException;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.config.AppProperties;
import com.dqh.dcs.printing.PrintingService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/PrintingController — in bản kiểm soát + tra cứu QR (port
 * src/routes/printing.ts, T-085, T-086).
 *
 * /verify/:token là route DUY NHẤT KHÔNG yêu cầu đăng nhập (DCS-7 §7.5) — chỉ
 * trả tình trạng, không nội dung.
 */
@Controller
public class PrintingController {

    private final Guards guards;
    private final PrintingService printing;
    private final AppProperties props;
    private final HttpErrors httpErrors;

    public PrintingController(Guards guards, PrintingService printing, AppProperties props,
            HttpErrors httpErrors) {
        this.guards = guards;
        this.printing = printing;
        this.props = props;
        this.httpErrors = httpErrors;
    }

    /** In bản kiểm soát — cần đăng nhập. */
    @PostMapping("/documents/{id}/print")
    public Object print(@PathVariable String id, @RequestParam(required = false) String location,
            @RequestParam(name = "site_id", required = false) String siteId, Model model,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            PrintingService.PrintRecord rec = printing.registerPrint(id, user.userId(),
                    (location == null || location.isEmpty()) ? null : location,
                    (siteId == null || siteId.isEmpty()) ? null : siteId,
                    props.getBasePath(), props.getPublicUrl());
            model.addAttribute("rec", rec);
            return "print-result";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    /** Thu hồi một bản sao in cụ thể tại hiện trường (cần đăng nhập + quyền). */
    @PostMapping("/documents/{id}/print/recall")
    public Object recall(@PathVariable String id,
            @RequestParam(name = "print_id", required = false) String printId, HttpServletRequest req,
            HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            printing.recallPrintedCopy(printId == null ? "" : printId, user.userId());
            return "redirect:/documents/" + id;
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    /** Tra cứu QR — KHÔNG yêu cầu đăng nhập. Chỉ trả tình trạng. */
    @GetMapping("/verify/{token}")
    public String verify(@PathVariable String token,
            @RequestParam(name = "site_id", required = false) String siteId, Model model) {
        String site = (siteId == null || siteId.trim().isEmpty()) ? null : siteId.trim();
        PrintingService.VerifyResult result = printing.verifyToken(token, site);
        printing.auditQrLookup(token);
        model.addAttribute("result", result);
        return "verify";
    }
}
