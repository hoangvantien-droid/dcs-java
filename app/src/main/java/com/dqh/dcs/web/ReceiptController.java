package com.dqh.dcs.web;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.common.Clock;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/ReceiptController — DC Site phản hồi receipt (port src/routes/receipts.ts,
 * Task 4). Kiểm quyền ở domain; controller điều phối + bắt BusinessException.
 */
@Controller
public class ReceiptController {

    private final Guards guards;
    private final PermissionService permissions;
    private final ReceiptService receipts;
    private final CatalogService catalog;
    private final HttpErrors httpErrors;

    public ReceiptController(Guards guards, PermissionService permissions, ReceiptService receipts,
            CatalogService catalog, HttpErrors httpErrors) {
        this.guards = guards;
        this.permissions = permissions;
        this.receipts = receipts;
        this.catalog = catalog;
        this.httpErrors = httpErrors;
    }

    /** Hàng chờ phản hồi của site (chỉ DC Site). */
    @GetMapping("/site-receipts")
    public String siteReceipts(Model model) {
        SessionUser user = guards.requireUser();
        List<String> siteIds = List.copyOf(permissions.siteIdsWithDocControl(user.userId()));
        if (siteIds.isEmpty()) {
            model.addAttribute("rows", List.of());
            model.addAttribute("notControl", true);
            model.addAttribute("recalledDocs", List.of());
            return "site-receipts";
        }
        model.addAttribute("rows", receipts.pendingReceiptsForSites(siteIds));
        model.addAttribute("notControl", false);
        model.addAttribute("recalledDocs", receipts.recalledDocsForSites(siteIds));
        return "site-receipts";
    }

    /** Màn phản hồi một receipt. */
    @GetMapping("/site-receipts/{id}")
    public String respondForm(@PathVariable String id, Model model, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        ReceiptService.PendingReceiptRow r = receipts.loadReceiptForView(id);
        if (r == null) {
            resp.sendError(404, "Không tìm thấy receipt");
            return null;
        }
        Set<String> siteIds = permissions.siteIdsWithDocControl(user.userId());
        if (!siteIds.contains(r.siteId())) {
            resp.sendError(403, "Không đủ thẩm quyền phản hồi receipt của site này");
            return null;
        }
        boolean selfRegistered = user.userId().equals(r.registrantUserId());
        model.addAttribute("r", r);
        model.addAttribute("detail", catalog.loadDetail(r.versionId()));
        model.addAttribute("sections", catalog.loadSections(r.versionId()));
        model.addAttribute("selfRegistered", selfRegistered);
        model.addAttribute("today", Clock.isoTodayVN());
        model.addAttribute("error", null);
        return "receipt-respond";
    }

    /** Chấp nhận. */
    @PostMapping("/site-receipts/{id}/accept")
    public void accept(@PathVariable String id, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        try {
            receipts.acceptReceipt(id, user.userId());
            httpErrors.sendOk(req, resp, "/site-receipts");
        } catch (BusinessException err) {
            if (!httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                throw err;
            }
        }
    }

    /** Từ chối (sai_dang_ky | sai_noi_dung + lý do bắt buộc). */
    @PostMapping("/site-receipts/{id}/reject")
    public void reject(@PathVariable String id,
            @RequestParam(name = "reject_type", required = false) String rejectType,
            @RequestParam(required = false) String note, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        if (!"sai_dang_ky".equals(rejectType) && !"sai_noi_dung".equals(rejectType)) {
            resp.sendError(400, "Loại từ chối không hợp lệ");
            return;
        }
        try {
            receipts.rejectReceipt(id, user.userId(), rejectType, note == null ? "" : note);
            httpErrors.sendOk(req, resp, "/site-receipts");
        } catch (BusinessException err) {
            if (!httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                throw err;
            }
        }
    }
}
