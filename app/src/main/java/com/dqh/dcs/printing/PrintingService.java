package com.dqh.dcs.printing;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.audit.ActorType;
import com.dqh.dcs.audit.AuditEntry;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.audit.EventType;
import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.lifecycle.LifecycleStatus;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * printing/PrintingService — bản in kiểm soát + tra cứu QR (port
 * src/domain/printing.ts, T-085, T-086).
 *
 * Mỗi lần in sinh printed_copies với verify_token NGẪU NHIÊN (DB default). Tra
 * cứu QR CHỈ trả TÌNH TRẠNG, tuyệt đối không trả nội dung. QR sinh bằng ZXing
 * (QĐ-I: qrcode→ZXing).
 */
@Service
public class PrintingService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final CatalogService catalog;

    public PrintingService(NamedParameterJdbcTemplate jdbc, AuditService audit, CatalogService catalog) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.catalog = catalog;
    }

    public record PrintRecord(String printId, String verifyToken, String docId, String versionNo,
            String printedAt, String verifyUrl, String qrSvg) {
    }

    public record VerifyResult(boolean found, String status, String docId, String versionNo,
            String currentVersionNo, String siteCode, String siteName, String receiptStatus,
            String applyDate, String rejectType) {
    }

    public record PrintedCopyRow(String printId, String verifyToken, String printedAt, String printedByName,
            String intendedLocation, String recallStatus) {
    }

    /** Ghi nhận một lần in — sinh verify_token ngẫu nhiên (DB default). */
    @Transactional
    public PrintRecord registerPrint(String versionId, String printedBy, String intendedLocation,
            String siteId, String basePath, String publicUrl) {
        if (!catalog.canViewVersion(printedBy, versionId)) {
            throw new BusinessException("print.no_permission", "Không có quyền in tài liệu này", 403);
        }
        List<String[]> vrows = jdbc.query(
                "SELECT status, doc_id, version_no FROM document_versions WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new String[] { rs.getString("status"), rs.getString("doc_id"),
                        rs.getString("version_no") });
        if (vrows.isEmpty()) {
            throw new BusinessException("print.no_version", "Không tìm thấy phiên bản");
        }
        String status = vrows.get(0)[0];
        String docId = vrows.get(0)[1];
        String versionNo = vrows.get(0)[2];
        if (!LifecycleStatus.isCurrentlyEffective(status)) {
            throw new BusinessException("print.not_effective", "Chỉ in được bản đang hiệu lực");
        }
        List<String[]> prow = jdbc.query(
                "INSERT INTO printed_copies (version_id, printed_by, intended_location, overlay_applied) "
                + "VALUES (CAST(:v AS uuid), CAST(:by AS uuid), :loc, TRUE) "
                + "RETURNING print_id, verify_token, printed_at::text AS printed_at",
                new MapSqlParameterSource().addValue("v", versionId).addValue("by", printedBy)
                        .addValue("loc", intendedLocation),
                (rs, i) -> new String[] { rs.getString("print_id"), rs.getString("verify_token"),
                        rs.getString("printed_at") });
        String printId = prow.get(0)[0];
        String verifyToken = prow.get(0)[1];
        String printedAt = prow.get(0)[2];
        audit.writeAudit(AuditEntry.of(EventType.in, "document_versions", versionId)
                .actorUserId(printedBy).afterState(Map.of("print_id", printId)));
        String siteQs = siteId == null || siteId.isEmpty()
                ? "" : "?site_id=" + URLEncoder.encode(siteId, StandardCharsets.UTF_8);
        String verifyUrl = publicUrl + basePath + "/verify/" + verifyToken + siteQs;
        String qrSvg = qrSvg(verifyUrl);
        return new PrintRecord(printId, verifyToken, docId, versionNo, printedAt, verifyUrl, qrSvg);
    }

    /**
     * Tra cứu QR — KHÔNG cần đăng nhập, CHỈ trả tình trạng. Tuyệt đối không trả
     * nội dung tài liệu.
     */
    public VerifyResult verifyToken(String token, String siteId) {
        List<String[]> rows = jdbc.query(
                "SELECT p.recall_status, v.status AS ver_status, v.doc_id, v.version_no, v.version_id "
                + "FROM printed_copies p JOIN document_versions v ON v.version_id = p.version_id "
                + "WHERE p.verify_token = :token",
                new MapSqlParameterSource("token", token),
                (rs, i) -> new String[] { rs.getString("recall_status"), rs.getString("ver_status"),
                        rs.getString("doc_id"), rs.getString("version_no"), rs.getString("version_id") });
        if (rows.isEmpty()) {
            return new VerifyResult(false, "khong_ro", null, null, null, null, null, null, null, null);
        }
        String recallStatus = rows.get(0)[0];
        String verStatus = rows.get(0)[1];
        String docId = rows.get(0)[2];
        String versionNo = rows.get(0)[3];
        String versionId = rows.get(0)[4];

        if ("da_thu_hoi".equals(recallStatus)) {
            return new VerifyResult(true, "ban_in_thu_hoi", docId, versionNo, null, null, null, null, null, null);
        }

        List<String> curRows = jdbc.queryForList(
                "SELECT version_no FROM document_versions WHERE doc_id = :d "
                + "AND status IN ('da_ban_hanh', 'hieu_luc') ORDER BY version_no DESC LIMIT 1",
                new MapSqlParameterSource("d", docId), String.class);
        String currentVersionNo = curRows.isEmpty() ? null : curRows.get(0);

        String status = "khong_ro";
        if (LifecycleStatus.isCurrentlyEffective(verStatus)) {
            status = "con_hieu_luc";
        } else if ("thu_hoi".equals(verStatus)) {
            status = "thu_hoi";
        } else if ("het_hieu_luc".equals(verStatus)) {
            status = "het_hieu_luc";
        }

        String siteCode = null;
        String siteName = null;
        String receiptStatus = null;
        String applyDate = null;
        String rejectType = null;
        if (siteId != null && !siteId.isEmpty()) {
            List<String[]> rr = jdbc.query(
                    "SELECT s.site_code, s.name AS site_name, r.status, r.apply_date::text AS apply_date, "
                    + "r.response_type FROM document_receipt r JOIN org_site s ON s.site_id = r.site_id "
                    + "WHERE r.version_id = CAST(:v AS uuid) AND r.site_id = CAST(:s AS uuid)",
                    new MapSqlParameterSource().addValue("v", versionId).addValue("s", siteId),
                    (rs, i) -> new String[] { rs.getString("site_code"), rs.getString("site_name"),
                            rs.getString("status"), rs.getString("apply_date"), rs.getString("response_type") });
            if (!rr.isEmpty()) {
                siteCode = rr.get(0)[0];
                siteName = rr.get(0)[1];
                receiptStatus = rr.get(0)[2];
                applyDate = rr.get(0)[3];
                rejectType = rr.get(0)[4];
                if ("con_hieu_luc".equals(status)) {
                    if ("cho_phan_hoi".equals(receiptStatus)) {
                        status = "chua_tiep_nhan";
                    } else if ("tu_choi".equals(receiptStatus)) {
                        status = "tu_choi_tiep_nhan";
                    }
                }
            }
        }
        return new VerifyResult(true, status, docId, versionNo, currentVersionNo, siteCode, siteName,
                receiptStatus, applyDate, rejectType);
    }

    /** Ghi vết một lượt tra cứu QR (không cần user). */
    @Transactional
    public void auditQrLookup(String token) {
        String objId = token == null ? null : (token.length() > 64 ? token.substring(0, 64) : token);
        audit.writeAudit(AuditEntry.of(EventType.tra_cuu_qr, "printed_copies", objId)
                .actorType(ActorType.he_thong));
    }

    /** Danh sách bản sao có kiểm soát đã in của một phiên bản. */
    public List<PrintedCopyRow> listPrintedCopies(String versionId) {
        return jdbc.query(
                "SELECT p.print_id, p.verify_token, p.printed_at::text AS printed_at, "
                + "u.full_name AS printed_by_name, p.intended_location, p.recall_status "
                + "FROM printed_copies p LEFT JOIN users u ON u.user_id = p.printed_by "
                + "WHERE p.version_id = CAST(:v AS uuid) ORDER BY p.printed_at DESC",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> new PrintedCopyRow(rs.getString("print_id"), rs.getString("verify_token"),
                        rs.getString("printed_at"), rs.getString("printed_by_name"),
                        rs.getString("intended_location"), rs.getString("recall_status")));
    }

    /** Thu hồi một bản sao in cụ thể tại hiện trường (đánh dấu da_thu_hoi). */
    @Transactional
    public void recallPrintedCopy(String printId, String userId) {
        List<String[]> rows = jdbc.query(
                "SELECT version_id, recall_status FROM printed_copies WHERE print_id = CAST(:p AS uuid) FOR UPDATE",
                new MapSqlParameterSource("p", printId),
                (rs, i) -> new String[] { rs.getString("version_id"), rs.getString("recall_status") });
        if (rows.isEmpty()) {
            throw new BusinessException("printrecall.not_found", "Không tìm thấy bản in");
        }
        String versionId = rows.get(0)[0];
        String recallStatus = rows.get(0)[1];
        if (!catalog.canViewVersion(userId, versionId)) {
            throw new BusinessException("printrecall.no_permission",
                    "Không đủ thẩm quyền thu hồi bản in này", 403);
        }
        if ("da_thu_hoi".equals(recallStatus)) {
            return;
        }
        jdbc.update("UPDATE printed_copies SET recall_status = 'da_thu_hoi' WHERE print_id = CAST(:p AS uuid)",
                new MapSqlParameterSource("p", printId));
        audit.writeAudit(AuditEntry.of(EventType.thu_hoi_ban_in, "printed_copies", printId)
                .actorUserId(userId).afterState(Map.of("recall_status", "da_thu_hoi")));
    }

    /** Sinh QR dạng SVG inline (lỗi #14) qua ZXing. */
    private static String qrSvg(String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 120, 120, hints);
            return matrixToSvg(matrix);
        } catch (Exception e) {
            return "";
        }
    }

    /** Dựng SVG path từ BitMatrix (không phụ thuộc thư viện SVG ngoài). */
    private static String matrixToSvg(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"120\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height)
                .append("\" shape-rendering=\"crispEdges\">");
        sb.append("<rect width=\"").append(width).append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\"/>");
        sb.append("<path fill=\"#000000\" d=\"");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y)) {
                    sb.append("M").append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        sb.append("\"/></svg>");
        return sb.toString();
    }

    /** Dùng khi cần ảnh PNG (giữ tiện ích; không dùng trực tiếp ở web). */
    static byte[] qrPng(String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 120, 120);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
