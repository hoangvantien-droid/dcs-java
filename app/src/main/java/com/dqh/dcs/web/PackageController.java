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
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.pkg.PackageService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/PackageController — gói ban hành [Legacy] (port src/routes/packages.ts,
 * T-078..081). Chỉ người Biên soạn (DC_AUTHOR) — chặn sớm ở controller.
 */
@Controller
public class PackageController {

    private final Guards guards;
    private final PermissionService permissions;
    private final PackageService packages;
    private final HttpErrors httpErrors;

    public PackageController(Guards guards, PermissionService permissions, PackageService packages,
            HttpErrors httpErrors) {
        this.guards = guards;
        this.permissions = permissions;
        this.packages = packages;
        this.httpErrors = httpErrors;
    }

    /** Cổng chặn: gói ban hành là việc của người Biên soạn. */
    private SessionUser requireRegistrar() {
        SessionUser user = guards.requireUser();
        if (!permissions.canRegisterDocuments(user.userId())) {
            throw new BusinessException("pkg.forbidden", "Không đủ thẩm quyền truy cập gói ban hành", 403);
        }
        return user;
    }

    @GetMapping("/packages")
    public String list(Model model) {
        SessionUser user = requireRegistrar();
        List<PackageService.PackageRow> ps = packages.listPackages();
        Map<String, List<PackageService.MemberRow>> membersByPkg = new LinkedHashMap<>();
        for (PackageService.PackageRow p : ps) {
            membersByPkg.put(p.packageId(), packages.packageMembers(p.packageId()));
        }
        model.addAttribute("packages", ps);
        model.addAttribute("membersByPkg", membersByPkg);
        model.addAttribute("attachable", packages.attachableFor(user.userId()));
        model.addAttribute("error", null);
        return "packages";
    }

    @PostMapping("/packages")
    public Object create(@RequestParam(required = false) String code,
            @RequestParam(required = false) String title,
            @RequestParam(name = "effective_date", required = false) String effectiveDate,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = requireRegistrar();
        try {
            packages.createPackage(new PackageService.CreatePackageInput(code == null ? "" : code.trim(),
                    title == null ? "" : title.trim(), effectiveDate == null ? "" : effectiveDate,
                    user.userId()));
            return "redirect:/packages";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    @PostMapping("/packages/attach")
    public Object attach(@RequestParam(name = "version_id", required = false) String versionId,
            @RequestParam(name = "package_id", required = false) String packageId,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = requireRegistrar();
        try {
            packages.attachToPackage(versionId == null ? "" : versionId, packageId == null ? "" : packageId,
                    user.userId());
            return "redirect:/packages";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    @PostMapping("/packages/move-date")
    public Object moveDate(@RequestParam(name = "package_id", required = false) String packageId,
            @RequestParam(name = "effective_date", required = false) String effectiveDate,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = requireRegistrar();
        try {
            packages.movePackageDate(packageId == null ? "" : packageId,
                    effectiveDate == null ? "" : effectiveDate, user.userId());
            return "redirect:/packages";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    @PostMapping("/packages/detach")
    public Object detach(@RequestParam(name = "version_id", required = false) String versionId,
            @RequestParam(required = false) String reason, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = requireRegistrar();
        try {
            packages.detachMember(versionId == null ? "" : versionId, reason == null ? "" : reason,
                    user.userId());
            return "redirect:/packages";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }
}
