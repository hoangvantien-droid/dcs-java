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
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.review.ReviewRow;
import com.dqh.dcs.review.ReviewService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/ReviewController — rà soát định kỳ (port src/routes/reviews.ts, T-076, T-077).
 */
@Controller
public class ReviewController {

    private final Guards guards;
    private final ReviewService reviews;
    private final PermissionService permissions;
    private final CurrentUserResolver currentUser;
    private final HttpErrors httpErrors;

    public ReviewController(Guards guards, ReviewService reviews, PermissionService permissions,
            CurrentUserResolver currentUser, HttpErrors httpErrors) {
        this.guards = guards;
        this.reviews = reviews;
        this.permissions = permissions;
        this.currentUser = currentUser;
        this.httpErrors = httpErrors;
    }

    @GetMapping("/reviews")
    public String reviews(Model model) {
        SessionUser user = guards.requireDocFlow();
        NavFlags nav = currentUser.nav();
        List<ReviewRow> rows = reviews.reviewBacklogForOwner(user.userId());
        List<ReviewRow> active = rows.stream().filter(r -> "dang_ra_soat".equals(r.status())).toList();
        List<ReviewRow> pending = rows.stream().filter(r -> "treo_cho_ban_moi".equals(r.status())).toList();
        List<ReviewRow> siteReminders = List.of();
        if (nav.isSiteDocControl()) {
            List<String> siteIds = List.copyOf(permissions.siteIdsWithDocControl(user.userId()));
            siteReminders = reviews.reviewBacklogForSites(siteIds);
        }
        model.addAttribute("active", active);
        model.addAttribute("pending", pending);
        model.addAttribute("siteReminders", siteReminders);
        model.addAttribute("canRegister", nav.canRegister());
        model.addAttribute("isSiteDocControl", nav.isSiteDocControl());
        return "reviews";
    }

    @PostMapping("/reviews/{id}/verdict")
    public Object verdict(@PathVariable String id,
            @RequestParam(required = false) String verdict, @RequestParam(required = false) String note,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireDocFlow();
        try {
            reviews.recordVerdict(id, verdict == null ? "" : verdict, note == null ? "" : note, user.userId());
            return "redirect:/reviews";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }
}
