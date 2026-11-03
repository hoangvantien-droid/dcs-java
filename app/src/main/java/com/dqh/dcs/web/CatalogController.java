package com.dqh.dcs.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.acknowledgement.AcknowledgementService;
import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.doctypes.DocTypes;
import com.dqh.dcs.lifecycle.LifecycleStatus;
import com.dqh.dcs.org.OrgService;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.permissions.Permissions;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/CatalogController — danh mục, chi tiết, xem/tải PDF, xác nhận đọc (port
 * src/routes/catalog.ts, T-037, T-038, T-036).
 */
@Controller
public class CatalogController {

    private final Guards guards;
    private final CurrentUserResolver currentUser;
    private final CatalogService catalog;
    private final PermissionService permissions;
    private final OrgService org;
    private final ReceiptService receipts;
    private final AcknowledgementService acknowledgements;
    private final com.dqh.dcs.printing.PrintingService printing;
    private final HttpErrors httpErrors;
    private final NamedParameterJdbcTemplate jdbc;
    private final Labels labels;

    public CatalogController(Guards guards, CurrentUserResolver currentUser, CatalogService catalog,
            PermissionService permissions, OrgService org, ReceiptService receipts,
            AcknowledgementService acknowledgements, com.dqh.dcs.printing.PrintingService printing,
            HttpErrors httpErrors, NamedParameterJdbcTemplate jdbc, Labels labels) {
        this.guards = guards;
        this.currentUser = currentUser;
        this.catalog = catalog;
        this.permissions = permissions;
        this.org = org;
        this.receipts = receipts;
        this.acknowledgements = acknowledgements;
        this.printing = printing;
        this.httpErrors = httpErrors;
        this.jdbc = jdbc;
        this.labels = labels;
    }

    private CatalogService.CatalogFilters parseFilters(String status, String q, String docType, String origin,
            String effFrom, String effTo, String preset, String sort, String siteId, String rolloutStatus) {
        return new CatalogService.CatalogFilters(trimOrNull(status), trimOrNull(q), trimOrNull(docType),
                trimOrNull(origin), trimOrNull(effFrom), trimOrNull(effTo), trimOrNull(preset),
                sort == null || sort.trim().isEmpty() ? "hieu_luc_moi" : sort.trim(),
                trimOrNull(siteId), trimOrNull(rolloutStatus));
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @GetMapping("/documents")
    public String documents(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(name = "doc_type", required = false) String docType,
            @RequestParam(required = false) String origin,
            @RequestParam(name = "eff_from", required = false) String effFrom,
            @RequestParam(name = "eff_to", required = false) String effTo,
            @RequestParam(required = false) String preset, @RequestParam(required = false) String sort,
            @RequestParam(name = "site_id", required = false) String siteId,
            @RequestParam(name = "rollout_status", required = false) String rolloutStatus, Model model) {
        SessionUser user = guards.requireDocFlow();
        NavFlags nav = currentUser.nav();
        CatalogService.CatalogFilters f = parseFilters(status, q, docType, origin, effFrom, effTo, preset,
                sort, siteId, rolloutStatus);
        List<CatalogService.CatalogRow> docs = catalog.listVisibleDocuments(user.userId(), f, nav.isReadOnly());
        model.addAttribute("docs", docs);
        model.addAttribute("statusFilter", f.status() == null ? "" : f.status());
        model.addAttribute("filters", f);
        model.addAttribute("sites", org.listSites());
        model.addAttribute("docTypes", DocTypes.DOC_TYPES);
        return "catalog";
    }

    @GetMapping("/documents/export.xlsx")
    public void exportXlsx(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(name = "doc_type", required = false) String docType,
            @RequestParam(required = false) String origin,
            @RequestParam(name = "eff_from", required = false) String effFrom,
            @RequestParam(name = "eff_to", required = false) String effTo,
            @RequestParam(required = false) String preset, @RequestParam(required = false) String sort,
            @RequestParam(name = "site_id", required = false) String siteId,
            @RequestParam(name = "rollout_status", required = false) String rolloutStatus,
            HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireDocFlow();
        if (!Permissions.canExportCatalog(permissions.roleCodesOf(user.userId()))) {
            resp.sendError(403, "Chỉ Biên soạn hoặc Kiểm soát tài liệu site được xuất danh mục");
            return;
        }
        CatalogService.CatalogFilters f = parseFilters(status, q, docType, origin, effFrom, effTo, preset,
                sort, siteId, rolloutStatus);
        List<CatalogService.CatalogRow> rows = catalog.listVisibleDocuments(user.userId(), f,
                currentUser.nav().isReadOnly());
        List<XlsxExport.Column<CatalogService.CatalogRow>> cols = List.of(
                new XlsxExport.Column<>("Số hiệu", CatalogService.CatalogRow::docId),
                new XlsxExport.Column<>("Phiên bản", CatalogService.CatalogRow::versionNo),
                new XlsxExport.Column<>("Tiêu đề", CatalogService.CatalogRow::title),
                new XlsxExport.Column<>("Loại", CatalogService.CatalogRow::docType),
                new XlsxExport.Column<>("Phạm vi", r -> labelScope(r.scopeLevel())),
                new XlsxExport.Column<>("Trạng thái", r -> labelDocStatus(r.status())),
                new XlsxExport.Column<>("Nguồn", r -> labelOrigin(r.origin())),
                new XlsxExport.Column<>("Ngày ban hành", r -> nz(r.issuedDate())),
                new XlsxExport.Column<>("Ngày hiệu lực", r -> nz(r.effectiveDate())),
                new XlsxExport.Column<>("Ngày rà soát kế tiếp", r -> nz(r.nextReviewDate())),
                new XlsxExport.Column<>("Ngày hết hạn", r -> nz(r.expiryDate())));
        Workbook wb = XlsxExport.buildWorkbook(cols, rows, "Danh mục");
        XlsxExport.stream(resp, wb, XlsxExport.exportFileName("catalog", LocalDateTime.now()));
    }

    // Xem trước PDF (inline).
    @GetMapping("/documents/{id}/preview")
    public void preview(@PathVariable String id, HttpServletResponse resp) throws IOException {
        serveFile(id, resp, false, true);
    }

    @GetMapping("/documents/{id}/download")
    public void download(@PathVariable String id, HttpServletResponse resp) throws IOException {
        serveFile(id, resp, true, false);
    }

    @GetMapping("/documents/{id}/view")
    public void view(@PathVariable String id, HttpServletResponse resp) throws IOException {
        serveFile(id, resp, false, false);
    }

    private record FileRow(String filePath, String docId, String versionNo, String status,
            String registrantUserId) {
    }

    private FileRow loadFileRow(String versionId) {
        List<FileRow> rows = jdbc.query(
                "SELECT v.file_path, v.doc_id, v.version_no, v.status, v.registrant_user_id "
                + "FROM document_versions v WHERE v.version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new FileRow(rs.getString("file_path"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("status"),
                        rs.getString("registrant_user_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** attachment=true → tải; preview=true → cho phép bản nháp của chủ. */
    private void serveFile(String versionId, HttpServletResponse resp, boolean attachment, boolean preview)
            throws IOException {
        SessionUser user = guards.requireUser();
        FileRow row = loadFileRow(versionId);
        if (row == null) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return;
        }
        boolean ok;
        if (preview && ("cho_xac_nhan".equals(row.status()) || "cho_hoan_thien".equals(row.status()))) {
            ok = user.userId().equals(row.registrantUserId());
        } else {
            if (!preview && !CatalogService.isCurrentStatus(row.status())) {
                resp.sendError(403, attachment ? "Chỉ tải được tệp của tài liệu hiện hành"
                        : "Chỉ xem được tệp của tài liệu hiện hành");
                return;
            }
            ok = catalog.canViewVersion(user.userId(), versionId);
        }
        if (!ok) {
            resp.sendError(403, "Không có quyền xem tài liệu này");
            return;
        }
        String absPath = row.filePath();
        if (absPath == null || !Files.exists(Path.of(absPath))) {
            resp.sendError(404, "Tệp không còn tồn tại");
            return;
        }
        resp.setHeader("Content-Type", "application/pdf");
        String disp = attachment ? "attachment" : "inline";
        resp.setHeader("Content-Disposition",
                disp + "; filename=\"" + row.docId() + "_v" + row.versionNo() + ".pdf\"");
        resp.getOutputStream().write(Files.readAllBytes(Path.of(absPath)));
        resp.getOutputStream().flush();
    }

    @GetMapping("/documents/{id}")
    public String detail(@PathVariable String id, Model model, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireDocFlow();
        NavFlags nav = currentUser.nav();
        CatalogService.DocumentDetail detail = catalog.loadDetail(id);
        if (detail == null) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        if (nav.isReadOnly() && !CatalogService.isCurrentStatus(detail.status())) {
            CatalogService.VersionRef current = catalog.currentVersionOf(detail.docId());
            if (current != null) {
                return "redirect:/documents/" + current.versionId();
            }
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        if (!catalog.canViewVersion(user.userId(), id, nav.isReadOnly())) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        List<CatalogService.SectionRow> sections = catalog.loadSections(id);
        List<CatalogService.LinkRow> links = catalog.loadLinks(id);
        List<ReceiptService.ReceiptRow> rs = receipts.listReceiptsForVersion(id);
        CatalogService.VersionRef current = !LifecycleStatus.isCurrentlyEffective(detail.status())
                ? catalog.currentVersionOf(detail.docId()) : null;
        AckState ackState = new AckState(false, false, false);
        if (LifecycleStatus.isCurrentlyEffective(detail.status())
                && acknowledgements.mustAcknowledge(detail.versionId(), user.userId())) {
            AcknowledgementService.AckState h = acknowledgements.hasAcknowledged(detail.versionId(),
                    user.userId());
            ackState = new AckState(!h.acknowledged(), h.acknowledged(), h.inherited());
        }
        boolean isRegistrant = user.userId().equals(detail.registrantUserId());
        model.addAttribute("d", detail);
        model.addAttribute("sections", sections);
        model.addAttribute("links", links);
        model.addAttribute("current", current);
        model.addAttribute("ackState", ackState);
        model.addAttribute("isRegistrant", isRegistrant);
        model.addAttribute("receipts", rs);
        model.addAttribute("sites", org.listSites());
        model.addAttribute("printedCopies", printing.listPrintedCopies(id));
        return "detail";
    }

    public record AckState(boolean show, boolean done, boolean inherited) {
        public boolean isShow() {
            return show;
        }

        public boolean isDone() {
            return done;
        }

        public boolean isInherited() {
            return inherited;
        }
    }

    @PostMapping("/documents/{id}/acknowledge")
    public Object acknowledge(@PathVariable String id, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        try {
            acknowledgements.acknowledge(id, user.userId());
            return "redirect:/documents/" + id;
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    /**
     * Xử lý nội dung tài liệu (bóc tách). Content domain best-effort để lại GĐ
     * sau (FEAT-002 CHƯA PORT content/); ở đây chỉ kiểm quyền chủ sở hữu rồi về
     * trang chi tiết (parse_status giữ nguyên, không chặn luồng).
     */
    @PostMapping("/documents/{id}/process")
    public Object process(@PathVariable String id, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        CatalogService.DocumentDetail detail = catalog.loadDetail(id);
        if (detail == null) {
            resp.sendError(404, "Không tìm thấy");
            return null;
        }
        if (!user.userId().equals(detail.registrantUserId())) {
            resp.sendError(403, "Chỉ người đăng ký được xử lý nội dung tài liệu này");
            return null;
        }
        return "redirect:/documents/" + id;
    }

    private String labelScope(String level) {
        return labels.scopeLabel(level);
    }

    private String labelDocStatus(String status) {
        return labels.docStatusLabel(status);
    }

    private String labelOrigin(String origin) {
        return labels.originLabel(origin);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
