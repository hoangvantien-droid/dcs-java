package com.dqh.dcs.web;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/HomeController — trang chủ "hôm nay tôi cần làm gì" (port src/routes/home.ts).
 *
 * DC Site: receipt chờ site mình phản hồi. Author: tài liệu mình ban hành bị
 * site báo sai sót (tu_choi) + site báo sai nội dung sau chấp nhận (IND-04b).
 */
@Controller
public class HomeController {

    private final Guards guards;
    private final PermissionService permissions;
    private final ReceiptService receipts;
    private final NamedParameterJdbcTemplate jdbc;

    public HomeController(Guards guards, PermissionService permissions, ReceiptService receipts,
            NamedParameterJdbcTemplate jdbc) {
        this.guards = guards;
        this.permissions = permissions;
        this.receipts = receipts;
        this.jdbc = jdbc;
    }

    public record VersionBrief(String versionId, String docId, String versionNo, String title) {
    }

    @GetMapping("/")
    public String home(Model model) {
        SessionUser user = guards.requireUser();
        List<String> siteIds = List.copyOf(permissions.siteIdsWithDocControl(user.userId()));
        List<ReceiptService.PendingReceiptRow> pendingReceipts =
                siteIds.isEmpty() ? List.of() : receipts.pendingReceiptsForSites(siteIds);

        List<VersionBrief> rejectedMine = jdbc.query(
                "SELECT DISTINCT v.version_id, v.doc_id, v.version_no, v.title "
                + "FROM document_versions v JOIN document_receipt r ON r.version_id = v.version_id "
                + "WHERE v.registrant_user_id = CAST(:u AS uuid) AND r.status = 'tu_choi' "
                + "ORDER BY v.version_id",
                new MapSqlParameterSource("u", user.userId()), HomeController::mapBrief);

        List<VersionBrief> contentErrorMine = jdbc.query(
                "SELECT DISTINCT v.version_id, v.doc_id, v.version_no, v.title "
                + "FROM document_versions v JOIN document_issue_report ir ON ir.version_id = v.version_id "
                + "WHERE v.registrant_user_id = CAST(:u AS uuid) "
                + "AND ir.status = 'bao_sai_noi_dung_sau_chap_nhan' ORDER BY v.version_id",
                new MapSqlParameterSource("u", user.userId()), HomeController::mapBrief);

        model.addAttribute("user", user);
        model.addAttribute("pendingReceipts", pendingReceipts);
        model.addAttribute("rejectedMine", rejectedMine);
        model.addAttribute("contentErrorMine", contentErrorMine);
        model.addAttribute("isSiteDocControl", !siteIds.isEmpty());
        return "home";
    }

    private static VersionBrief mapBrief(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new VersionBrief(rs.getString("version_id"), rs.getString("doc_id"),
                rs.getString("version_no"), rs.getString("title"));
    }
}
