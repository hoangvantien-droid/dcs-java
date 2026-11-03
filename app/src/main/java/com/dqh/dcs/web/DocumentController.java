package com.dqh.dcs.web;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.distribution.DistributionService;
import com.dqh.dcs.doctypes.DocTypes;
import com.dqh.dcs.filestore.FileStore;
import com.dqh.dcs.lifecycle.AggregateStatus;
import com.dqh.dcs.lifecycle.LifecycleStatus;
import com.dqh.dcs.lifecycle.ReceiptTally;
import com.dqh.dcs.org.OrgService;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.MetadataPatch;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.registration.DocumentInput;
import com.dqh.dcs.registration.RegistrationService;
import com.dqh.dcs.registration.VersionInput;
import com.dqh.dcs.roles.ScopeLevel;
import com.dqh.dcs.session.SessionUser;
import com.dqh.dcs.web.Labels.AggregateDisplay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * web/DocumentController — form đăng ký + tài liệu của tôi (port
 * src/routes/documents.ts, T-021..026). Form một trang gộp 3 khối.
 */
@Controller
public class DocumentController {

    private final Guards guards;
    private final RegistrationService registration;
    private final ReceiptService receipts;
    private final PermissionService permissions;
    private final OrgService org;
    private final DistributionService distribution;
    private final FileStore fileStore;
    private final CatalogService catalog;
    private final Labels labels;
    private final HttpErrors httpErrors;
    private final NamedParameterJdbcTemplate jdbc;

    public DocumentController(Guards guards, RegistrationService registration, ReceiptService receipts,
            PermissionService permissions, OrgService org, DistributionService distribution,
            FileStore fileStore, CatalogService catalog, Labels labels, HttpErrors httpErrors,
            NamedParameterJdbcTemplate jdbc) {
        this.guards = guards;
        this.registration = registration;
        this.receipts = receipts;
        this.permissions = permissions;
        this.org = org;
        this.distribution = distribution;
        this.fileStore = fileStore;
        this.catalog = catalog;
        this.labels = labels;
        this.httpErrors = httpErrors;
        this.jdbc = jdbc;
    }

    private static String t(String s) {
        return s == null ? "" : s.trim();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record SupersedeRow(String versionId, String docId, String versionNo, String status) {
    }

    private List<SupersedeRow> listSupersedeable() {
        return jdbc.query(
                "SELECT version_id, doc_id, version_no, status FROM document_versions "
                + "WHERE status IN ('da_ban_hanh', 'hieu_luc', 'da_dang_ky') ORDER BY doc_id, version_no DESC",
                new MapSqlParameterSource(),
                (rs, i) -> new SupersedeRow(rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("status")));
    }

    private String supersedeOptions(List<SupersedeRow> rows, String selected) {
        StringBuilder sb = new StringBuilder();
        for (SupersedeRow v : rows) {
            boolean sel = v.versionId().equals(selected);
            sb.append("<option value=\"").append(esc(v.versionId())).append('"')
                    .append(sel ? " selected" : "").append('>').append(esc(v.docId())).append(" v")
                    .append(esc(v.versionNo())).append(" (").append(esc(v.status())).append(")</option>");
        }
        return sb.toString();
    }

    /** Chuẩn hoá site_ids từ form (loại rỗng/trùng, giữ thứ tự). */
    private static List<String> normalizeSiteIds(List<String> raw) {
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.trim().isEmpty()) {
                    seen.add(s.trim());
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private void addRegisterModel(Model model, String selected, List<String> selectedSiteIds) {
        model.addAttribute("bus", org.listBu());
        model.addAttribute("sites", org.listSites());
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("supersedeOptions", supersedeOptions(listSupersedeable(), selected));
        model.addAttribute("docTypes", DocTypes.DOC_TYPES);
        model.addAttribute("selectedSiteIds", selectedSiteIds == null ? List.of() : selectedSiteIds);
    }

    // Form đăng ký — chỉ Biên soạn (DC_AUTHOR).
    @GetMapping("/register")
    public Object registerForm(Model model, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        if (!permissions.canRegisterDocuments(user.userId())) {
            resp.sendError(403, "Không có quyền đăng ký ban hành tài liệu");
            return null;
        }
        addRegisterModel(model, null, List.of());
        model.addAttribute("error", null);
        model.addAttribute("values", Map.of());
        return "register";
    }

    @PostMapping("/register")
    public Object register(@RequestParam Map<String, String> form,
            @RequestParam(name = "site_ids", required = false) List<String> siteIds,
            @RequestParam(name = "file", required = false) MultipartFile file, Model model,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        List<String> sites = normalizeSiteIds(siteIds);
        try {
            String filePath = t(form.get("file_path"));
            String fileHash = t(form.get("file_hash"));
            if (file != null && !file.isEmpty()) {
                FileStore.Stored stored = fileStore.storeFile(file.getBytes(),
                        t(form.get("doc_id")).isEmpty() ? "doc.pdf" : t(form.get("doc_id")) + ".pdf");
                filePath = stored.filePath();
                fileHash = stored.fileHash();
            }
            if (filePath.isEmpty()) {
                throw new BusinessException("reg.file", "Bắt buộc tải lên tệp PDF của tài liệu");
            }
            DocumentInput doc = docInput(form);
            VersionInput ver = new VersionInput(
                    t(form.get("doc_id")), t(form.get("version_no")), t(form.get("title")),
                    nz(form.get("issued_date")), emptyToNull(form.get("external_effective_date")),
                    nz(form.get("effective_date")), parseInt(form.get("review_cycle_months"), 12),
                    emptyToNull(form.get("expiry_date")), "thu_cong", filePath, fileHash,
                    user.userId(), nz(form.get("owner_department_id")),
                    emptyToNull(form.get("supersedes_version_id")),
                    emptyToNull(form.get("change_significance")), emptyToNull(form.get("change_summary")));
            registration.submitRegistration(doc, ver, sites);
            return "redirect:/my-documents";
        } catch (BusinessException err) {
            resp.setStatus(err.getHttpStatus());
            addRegisterModel(model, form.get("supersedes_version_id"), sites);
            model.addAttribute("error", err.getMessage());
            model.addAttribute("values", form);
            return "register";
        }
    }

    // Lưu nháp (T-024).
    @PostMapping("/register/draft")
    public Object draft(@RequestParam Map<String, String> form,
            @RequestParam(name = "file", required = false) MultipartFile file, Model model,
            HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            String filePath = t(form.get("file_path"));
            String fileHash = t(form.get("file_hash"));
            if (file != null && !file.isEmpty()) {
                FileStore.Stored stored = fileStore.storeFile(file.getBytes(),
                        t(form.get("doc_id")).isEmpty() ? "doc.pdf" : t(form.get("doc_id")) + ".pdf");
                filePath = stored.filePath();
                fileHash = stored.fileHash();
            }
            registration.saveDraft(draftDocInput(form), draftVersionInput(form, user, filePath, fileHash));
            return "redirect:/my-documents";
        } catch (BusinessException err) {
            resp.setStatus(err.getHttpStatus());
            addRegisterModel(model, form.get("supersedes_version_id"), List.of());
            model.addAttribute("error", err.getMessage());
            model.addAttribute("values", form);
            return "register";
        }
    }

    // Đếm số người sẽ nhận theo bộ phận (T-023) — htmx.
    @GetMapping("/register/recipient-count")
    public void recipientCount(@RequestParam(name = "owner_department_id", required = false) String dept,
            @RequestParam(required = false) String fg, HttpServletResponse resp) throws IOException {
        guards.requireUser();
        int count = 0;
        if (dept != null && !dept.isEmpty()) {
            count = distribution.countRecipientsFor(dept, (fg == null || fg.isEmpty()) ? null : fg);
        }
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write("<span>Số người sẽ nhận nếu phân phối tới bộ phận này: <b>" + count + "</b></span>");
    }

    // Tài liệu của tôi.
    @GetMapping("/my-documents")
    public Object myDocuments(Model model, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        if (!permissions.canRegisterDocuments(user.userId())) {
            resp.sendError(403, "Chỉ người biên soạn có mục Tài liệu của tôi");
            return null;
        }
        model.addAttribute("docs", assembleMyDocuments(user.userId()));
        return "my-documents";
    }

    @GetMapping("/my-documents/export.xlsx")
    public void myDocsExport(HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        if (!permissions.canRegisterDocuments(user.userId())) {
            resp.sendError(403, "Chỉ người biên soạn được xuất Excel tài liệu của mình");
            return;
        }
        List<MyDocRow> docs = assembleMyDocuments(user.userId());
        List<XlsxExport.Column<MyDocRow>> cols = List.of(
                new XlsxExport.Column<>("Số hiệu", MyDocRow::docId),
                new XlsxExport.Column<>("Phiên bản", MyDocRow::versionNo),
                new XlsxExport.Column<>("Tiêu đề", MyDocRow::title),
                new XlsxExport.Column<>("Trạng thái", d -> labels.docStatusLabel(d.status())),
                new XlsxExport.Column<>("Site xác nhận", d -> d.total() > 0 ? d.accepted() + "/" + d.total() : ""),
                new XlsxExport.Column<>("Tình trạng ban hành", d -> (d.total() > 0 || d.hasPostAcceptContentError())
                        ? labels.aggregateDisplay(d.aggregate().name(), d.hasContentReject(),
                                d.hasPostAcceptContentError()).label()
                        : "Chưa phát hành"),
                new XlsxExport.Column<>("Lý do từ chối", d -> nz(d.rejectionReason())));
        Workbook wb = XlsxExport.buildWorkbook(cols, docs, "Tài liệu của tôi");
        XlsxExport.stream(resp, wb, XlsxExport.exportFileName("my-documents", LocalDateTime.now()));
    }

    public record MyDocRow(String versionId, String docId, String versionNo, String title, String status,
            String effectiveDate, String rejectionReason, String parseStatus, String parseNote, int total,
            int accepted, int rejected, int pending, int contentRejected, AggregateStatus aggregate,
            boolean hasContentReject, boolean hasPostAcceptContentError) {
        public String getStatus() {
            return status;
        }

        public AggregateStatus getAggregate() {
            return aggregate;
        }
    }

    List<MyDocRow> assembleMyDocuments(String userId) {
        return jdbc.query(
                "SELECT v.version_id, v.doc_id, v.version_no, v.title, v.status, "
                + "v.effective_date::text AS effective_date, v.rejection_reason, v.parse_status, v.parse_note, "
                + "count(r.*)::int AS total, "
                + "count(r.*) FILTER (WHERE r.status = 'chap_nhan')::int AS accepted, "
                + "count(r.*) FILTER (WHERE r.status = 'tu_choi')::int AS rejected, "
                + "count(r.*) FILTER (WHERE r.status = 'cho_phan_hoi')::int AS pending, "
                + "count(r.*) FILTER (WHERE r.status = 'tu_choi' AND r.response_type = 'sai_noi_dung')::int AS content_rejected, "
                + "(SELECT count(*)::int FROM document_issue_report ir WHERE ir.version_id = v.version_id "
                + "  AND ir.status = 'bao_sai_noi_dung_sau_chap_nhan') AS post_accept_content_error "
                + "FROM document_versions v LEFT JOIN document_receipt r ON r.version_id = v.version_id "
                + "WHERE v.registrant_user_id = CAST(:u AS uuid) "
                + "GROUP BY v.version_id, v.doc_id, v.version_no, v.title, v.status, v.effective_date, "
                + "  v.rejection_reason, v.parse_status, v.parse_note, v.submitted_at, v.updated_at "
                + "ORDER BY v.submitted_at DESC NULLS LAST, v.updated_at DESC",
                new MapSqlParameterSource("u", userId),
                (rs, i) -> {
                    int total = rs.getInt("total");
                    int accepted = rs.getInt("accepted");
                    int rejected = rs.getInt("rejected");
                    int pending = rs.getInt("pending");
                    int contentRejected = rs.getInt("content_rejected");
                    int postAccept = rs.getInt("post_accept_content_error");
                    AggregateStatus agg = LifecycleStatus.aggregateStatusOf(
                            new ReceiptTally(total, accepted, rejected, pending));
                    return new MyDocRow(rs.getString("version_id"), rs.getString("doc_id"),
                            rs.getString("version_no"), rs.getString("title"), rs.getString("status"),
                            rs.getString("effective_date"), rs.getString("rejection_reason"),
                            rs.getString("parse_status"), rs.getString("parse_note"), total, accepted,
                            rejected, pending, contentRejected, agg, contentRejected > 0, postAccept > 0);
                });
    }

    // Tình trạng ban hành theo site (Task 5).
    @GetMapping("/my-documents/{id}/issuance")
    public Object issuance(@PathVariable String id, Model model, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        List<String[]> vrows = jdbc.query(
                "SELECT v.version_id, v.doc_id, v.version_no, v.title, v.status, v.registrant_user_id, "
                + "d.scope_level, d.scope_bu_id, d.scope_site_id, d.scope_dept_id "
                + "FROM document_versions v JOIN documents d ON d.doc_id = v.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", id),
                (rs, i) -> new String[] { rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"), rs.getString("status"),
                        rs.getString("registrant_user_id"), rs.getString("scope_level"),
                        rs.getString("scope_bu_id"), rs.getString("scope_site_id"),
                        rs.getString("scope_dept_id") });
        if (vrows.isEmpty()) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        String[] v = vrows.get(0);
        if (!user.userId().equals(v[5])) {
            resp.sendError(403, "Chỉ người đăng ký xem được tình trạng ban hành tài liệu này");
            return null;
        }
        List<ReceiptService.ReceiptRow> rs = receipts.listReceiptsForVersion(id);
        ReceiptTally tally = receipts.receiptTally(id);
        AggregateStatus aggregate = LifecycleStatus.aggregateStatusOf(tally);
        boolean canEditMetadata = tally.accepted() == 0 || tally.rejected() > 0;
        boolean canRecall = "da_ban_hanh".equals(v[4]);
        boolean canDelete = tally.accepted() == 0;
        boolean hasContentReject = rs.stream()
                .anyMatch(r -> "tu_choi".equals(r.status()) && "sai_noi_dung".equals(r.responseType()));
        boolean hasMetaReject = rs.stream()
                .anyMatch(r -> "tu_choi".equals(r.status()) && "sai_dang_ky".equals(r.responseType()));
        Integer peN = jdbc.queryForObject(
                "SELECT count(*)::int AS n FROM document_issue_report "
                + "WHERE version_id = CAST(:v AS uuid) AND status = 'bao_sai_noi_dung_sau_chap_nhan'",
                new MapSqlParameterSource("v", id), Integer.class);
        boolean hasPostAcceptContentError = peN != null && peN > 0;
        List<ReceiptService.SiteOption> candidates = receipts.candidateSitesForScope(v[6], v[7], v[8], v[9]);
        Set<String> existingSiteIds = new java.util.HashSet<>();
        for (ReceiptService.ReceiptRow r : rs) {
            existingSiteIds.add(r.siteId());
        }
        List<ReceiptService.SiteOption> addableNew = candidates.stream()
                .filter(c -> !existingSiteIds.contains(c.siteId())).toList();
        List<ReceiptService.ReceiptRow> republishable = rs.stream()
                .filter(r -> "tu_choi".equals(r.status()) && "sai_dang_ky".equals(r.responseType())).toList();
        model.addAttribute("v", new IssuanceVersion(v[0], v[1], v[2], v[3], v[4]));
        model.addAttribute("receipts", rs);
        model.addAttribute("tally", tally);
        model.addAttribute("aggregate", aggregate.name());
        model.addAttribute("anyAccepted", tally.accepted() > 0);
        model.addAttribute("canEditMetadata", canEditMetadata);
        model.addAttribute("canRecall", canRecall);
        model.addAttribute("canDelete", canDelete);
        model.addAttribute("hasContentReject", hasContentReject);
        model.addAttribute("hasMetaReject", hasMetaReject);
        model.addAttribute("hasPostAcceptContentError", hasPostAcceptContentError);
        model.addAttribute("addableNew", addableNew);
        model.addAttribute("republishable", republishable);
        model.addAttribute("blockedByContentReject", hasContentReject);
        AggregateDisplay ad = labels.aggregateDisplay(aggregate.name(), hasContentReject,
                hasPostAcceptContentError);
        model.addAttribute("aggregateDisplay", ad);
        return "issuance-status";
    }

    public record IssuanceVersion(String versionId, String docId, String versionNo, String title,
            String status) {
    }

    // Sửa metadata tại chỗ.
    @GetMapping("/my-documents/{id}/edit-metadata")
    public Object editMetadataForm(@PathVariable String id, Model model, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version_id, doc_id, version_no, title, issued_date::text AS issued_date, "
                + "effective_date::text AS effective_date, review_cycle_months, expiry_date::text AS expiry_date, "
                + "owner_department_id, status, registrant_user_id FROM document_versions "
                + "WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", id));
        if (rows.isEmpty()) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        Map<String, Object> v = rows.get(0);
        if (!user.userId().equals(String.valueOf(v.get("registrant_user_id")))) {
            resp.sendError(403, "Chỉ người đăng ký được sửa");
            return null;
        }
        boolean accepted = receipts.hasAnyAcceptance(id);
        model.addAttribute("v", v);
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("locked", accepted);
        model.addAttribute("error", null);
        return "edit-metadata";
    }

    @PostMapping("/my-documents/{id}/edit-metadata")
    public Object editMetadata(@PathVariable String id, @RequestParam Map<String, String> form,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        boolean expirySubmitted = form.containsKey("expiry_date");
        String expiryPatch = expirySubmitted ? t(form.get("expiry_date")) : null;
        try {
            MetadataPatch patch = new MetadataPatch(
                    emptyToNull(t(form.get("title"))), emptyToNull(form.get("issued_date")),
                    emptyToNull(form.get("effective_date")),
                    form.get("review_cycle_months") != null && !form.get("review_cycle_months").isEmpty()
                            ? Integer.valueOf(form.get("review_cycle_months")) : null,
                    expiryPatch, emptyToNull(form.get("owner_department_id")));
            receipts.editVersionMetadata(id, user.userId(), patch);
            return "redirect:/my-documents/" + id + "/issuance";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    // Thu hồi bởi Author.
    @GetMapping("/my-documents/{id}/recall")
    public Object recallForm(@PathVariable String id, Model model, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        List<String[]> rows = jdbc.query(
                "SELECT version_id, doc_id, version_no, title, registrant_user_id FROM document_versions "
                + "WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", id),
                (rs, i) -> new String[] { rs.getString("version_id"), rs.getString("doc_id"),
                        rs.getString("version_no"), rs.getString("title"),
                        rs.getString("registrant_user_id") });
        if (rows.isEmpty()) {
            resp.sendError(404, "Không tìm thấy tài liệu");
            return null;
        }
        String[] v = rows.get(0);
        if (!user.userId().equals(v[4])) {
            resp.sendError(403, "Chỉ người đăng ký được thu hồi");
            return null;
        }
        model.addAttribute("v", new IssuanceVersion(v[0], v[1], v[2], v[3], null));
        model.addAttribute("tally", receipts.receiptTally(id));
        model.addAttribute("error", null);
        return "author-recall";
    }

    @PostMapping("/my-documents/{id}/recall")
    public Object recall(@PathVariable String id, @RequestParam(required = false) String reason,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        try {
            receipts.recallByAuthor(id, user.userId(), reason == null ? "" : reason);
            return "redirect:/my-documents";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    // Phát hành thêm site + phát hành lại (eff_<site_id> per-site).
    @PostMapping("/my-documents/{id}/publish-sites")
    public Object publishSites(@PathVariable String id, @RequestParam Map<String, String> form,
            @RequestParam(name = "site_ids", required = false) List<String> siteIds,
            HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        Map<String, String> siteEffectiveDates = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey().startsWith("eff_")) {
                String siteId = e.getKey().substring("eff_".length());
                String dateStr = t(e.getValue());
                if (!siteId.isEmpty() && !dateStr.isEmpty()) {
                    siteEffectiveDates.put(siteId, dateStr);
                }
            }
        }
        try {
            receipts.publishAdditionalSites(id, user.userId(), normalizeSiteIds(siteIds), siteEffectiveDates);
            return "redirect:/my-documents/" + id + "/issuance";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    // Xóa bản ban hành.
    @PostMapping("/my-documents/{id}/delete")
    public Object delete(@PathVariable String id, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        try {
            receipts.deleteUnacceptedVersion(id, user.userId());
            return "redirect:/my-documents";
        } catch (BusinessException err) {
            if (httpErrors.sendBusinessErrorHtmx(req, resp, err)) {
                return null;
            }
            throw err;
        }
    }

    // Sửa nháp.
    @GetMapping("/my-documents/{id}/edit")
    public Object editDraftForm(@PathVariable String id, Model model, HttpServletResponse resp)
            throws IOException {
        SessionUser user = guards.requireUser();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.version_id, v.doc_id, v.version_no, v.title, v.issued_date::text AS issued_date, "
                + "v.external_effective_date::text AS external_effective_date, "
                + "v.effective_date::text AS effective_date, v.review_cycle_months, "
                + "v.expiry_date::text AS expiry_date, v.file_path, v.file_hash, v.owner_department_id, "
                + "v.supersedes_version_id, v.change_significance, v.change_summary, "
                + "d.doc_type, d.origin, d.scope_level, d.scope_bu_id, d.scope_site_id, d.scope_dept_id "
                + "FROM document_versions v JOIN documents d ON d.doc_id = v.doc_id "
                + "WHERE v.version_id = CAST(:v AS uuid) AND v.registrant_user_id = CAST(:u AS uuid) "
                + "AND v.status = 'cho_hoan_thien'",
                new MapSqlParameterSource().addValue("v", id).addValue("u", user.userId()));
        if (rows.isEmpty()) {
            resp.sendError(404, "Không tìm thấy nháp để sửa");
            return null;
        }
        Map<String, Object> draft = rows.get(0);
        addRegisterModel(model, String.valueOf(draft.get("supersedes_version_id")), List.of());
        model.addAttribute("error", null);
        model.addAttribute("values", draft);
        model.addAttribute("editVersionId", draft.get("version_id"));
        return "register";
    }

    @PostMapping("/my-documents/{id}/edit")
    public Object editDraft(@PathVariable String id, @RequestParam Map<String, String> form,
            @RequestParam(name = "file", required = false) MultipartFile file, Model model,
            HttpServletResponse resp) throws IOException {
        SessionUser user = guards.requireUser();
        List<String> existing = jdbc.queryForList(
                "SELECT version_id FROM document_versions WHERE version_id = CAST(:v AS uuid) "
                + "AND registrant_user_id = CAST(:u AS uuid) AND status = 'cho_hoan_thien'",
                new MapSqlParameterSource().addValue("v", id).addValue("u", user.userId()), String.class);
        if (existing.isEmpty()) {
            resp.sendError(404, "Không tìm thấy nháp để sửa");
            return null;
        }
        try {
            String filePath = t(form.get("file_path"));
            String fileHash = t(form.get("file_hash"));
            if (file != null && !file.isEmpty()) {
                FileStore.Stored stored = fileStore.storeFile(file.getBytes(),
                        t(form.get("doc_id")).isEmpty() ? "doc.pdf" : t(form.get("doc_id")) + ".pdf");
                filePath = stored.filePath();
                fileHash = stored.fileHash();
            }
            registration.saveDraft(draftDocInput(form), draftVersionInput(form, user, filePath, fileHash));
            return "redirect:/my-documents";
        } catch (BusinessException err) {
            resp.setStatus(err.getHttpStatus());
            addRegisterModel(model, form.get("supersedes_version_id"), List.of());
            model.addAttribute("error", err.getMessage());
            model.addAttribute("values", form);
            return "register";
        }
    }

    // ---- helpers dựng input ----

    private DocumentInput docInput(Map<String, String> b) {
        return new DocumentInput(t(b.get("doc_id")),
                b.get("doc_type") == null || b.get("doc_type").isEmpty() ? "SOP" : b.get("doc_type"),
                b.get("origin") == null || b.get("origin").isEmpty() ? "noi_bo" : b.get("origin"),
                parseScope(b.get("scope_level"), "site"), emptyToNull(b.get("scope_bu_id")),
                emptyToNull(b.get("scope_site_id")), emptyToNull(b.get("scope_dept_id")));
    }

    private DocumentInput draftDocInput(Map<String, String> b) {
        String rawLevel = t(b.get("scope_level"));
        boolean hasPointer = ("bu".equals(rawLevel) && !t(b.get("scope_bu_id")).isEmpty())
                || ("site".equals(rawLevel) && !t(b.get("scope_site_id")).isEmpty())
                || ("dept".equals(rawLevel) && !t(b.get("scope_dept_id")).isEmpty());
        String level = ("group".equals(rawLevel) || hasPointer) ? rawLevel : "group";
        return new DocumentInput(t(b.get("doc_id")),
                b.get("doc_type") == null || b.get("doc_type").isEmpty() ? "SOP" : b.get("doc_type"),
                b.get("origin") == null || b.get("origin").isEmpty() ? "noi_bo" : b.get("origin"),
                ScopeLevel.valueOf(level), emptyToNull(b.get("scope_bu_id")),
                emptyToNull(b.get("scope_site_id")), emptyToNull(b.get("scope_dept_id")));
    }

    private VersionInput draftVersionInput(Map<String, String> b, SessionUser user, String filePath,
            String fileHash) {
        String versionNo = t(b.get("version_no")).isEmpty() ? "nhap" : t(b.get("version_no"));
        String title = t(b.get("title")).isEmpty() ? "(nháp)" : t(b.get("title"));
        return new VersionInput(t(b.get("doc_id")), versionNo, title, null, null, null, 0, null, "thu_cong",
                filePath.isEmpty() ? "(chưa có tệp)" : filePath, fileHash.isEmpty() ? "draft" : fileHash,
                user.userId(), nz(b.get("owner_department_id")), null, null, null);
    }

    private static ScopeLevel parseScope(String raw, String def) {
        String v = (raw == null || raw.isEmpty()) ? def : raw;
        try {
            return ScopeLevel.valueOf(v);
        } catch (IllegalArgumentException e) {
            return ScopeLevel.valueOf(def);
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
