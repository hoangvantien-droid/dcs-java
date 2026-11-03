package com.dqh.dcs.web;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * web/Labels — nguồn DUY NHẤT ánh xạ mã nghiệp vụ → nhãn tiếng Việt (port
 * src/views/labels.ts). Mọi view phân giải mã qua đây, không in thẳng mã enum.
 * CHỈ đổi chữ hiển thị — KHÔNG đổi giá trị enum / mã nghiệp vụ.
 */
@Component("labels")
public class Labels {

    public static final Map<String, String> DOC_STATUS_LABELS = Map.ofEntries(
            Map.entry("cho_hoan_thien", "Nháp chờ hoàn thiện"),
            Map.entry("da_ban_hanh", "Đã ban hành"),
            Map.entry("het_hieu_luc", "Hết hiệu lực"),
            Map.entry("thu_hoi", "Đã thu hồi"),
            Map.entry("cho_xac_nhan", "Chờ xác nhận"),
            Map.entry("tu_choi", "Bị trả lại"),
            Map.entry("da_dang_ky", "Đã xác nhận, chờ hiệu lực"),
            Map.entry("hieu_luc", "Đang hiệu lực"));

    public static final Map<String, String> RECEIPT_STATUS_LABELS = Map.of(
            "cho_phan_hoi", "Chờ phản hồi",
            "chap_nhan", "Đã chấp nhận",
            "tu_choi", "Đã từ chối");

    public static final Map<String, String> RECEIPT_REJECT_TYPE_LABELS = Map.of(
            "sai_dang_ky", "Sai thông tin đăng ký",
            "sai_noi_dung", "Sai nội dung tài liệu");

    public static final Map<String, String> ISSUE_REPORT_STATUS_LABELS = Map.of(
            "moi", "Mới — chờ xem xét",
            "chuyen_phan_hoi", "Đã chuyển thành phản hồi",
            "bac_bo", "Đã bác bỏ",
            "bao_sai_noi_dung_sau_chap_nhan", "Báo sai nội dung sau khi đã chấp nhận");

    public static final Map<String, String> AGGREGATE_STATUS_LABELS = Map.of(
            "chua_phat_hanh", "Chưa phát hành",
            "dang_cho", "Đang chờ phản hồi",
            "mot_phan", "Một phần đã áp dụng",
            "tranh_chap", "Có site báo sai sót",
            "toan_bo", "Toàn bộ đã áp dụng");

    public static final Map<String, String> ROLE_LABELS = Map.of(
            "DC_ADMIN", "Quản trị hệ thống",
            "DC_AUTHOR", "Biên soạn tài liệu",
            "DC_SITE_DOC_CONTROL", "Kiểm soát tài liệu (site)",
            "DC_READER", "Người đọc",
            "DC_DEPT_HEAD", "Phê duyệt (vai cũ: theo Dept chính)",
            "DC_SITE_QA_MANAGER", "Phê duyệt (vai cũ: QA Site)",
            "DC_BU_OWNER", "Phê duyệt (vai cũ: Chủ BU)",
            "DC_GROUP_OWNER", "Phê duyệt (vai cũ: Chủ Group)");

    public static final Map<String, String> ORIGIN_LABELS = Map.of(
            "noi_bo", "Nội bộ",
            "ngoai_bo", "Ngoại bộ");

    public static final Map<String, String> SCOPE_LABELS = Map.of(
            "group", "Toàn Tập đoàn",
            "bu", "Khối (BU)",
            "site", "Nhà máy / Chi nhánh",
            "dept", "Phòng/Ban",
            "department", "Phòng/Ban");

    private static String lookup(Map<String, String> map, String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        return map.getOrDefault(code, code);
    }

    public String docStatusLabel(String code) {
        return lookup(DOC_STATUS_LABELS, code);
    }

    public String scopeLabel(String level) {
        return lookup(SCOPE_LABELS, level);
    }

    public String originLabel(String code) {
        return lookup(ORIGIN_LABELS, code);
    }

    public String roleLabel(String code) {
        return lookup(ROLE_LABELS, code);
    }

    public String receiptStatusLabel(String code) {
        return lookup(RECEIPT_STATUS_LABELS, code);
    }

    public String receiptRejectTypeLabel(String code) {
        return lookup(RECEIPT_REJECT_TYPE_LABELS, code);
    }

    public String issueReportStatusLabel(String code) {
        return lookup(ISSUE_REPORT_STATUS_LABELS, code);
    }

    public String aggregateStatusLabel(String code) {
        return lookup(AGGREGATE_STATUS_LABELS, code);
    }

    /** Nhãn + lớp màu cho trạng thái tổng hợp (port aggregateDisplay). */
    public record AggregateDisplay(String label, String cls) {
        public String getLabel() {
            return label;
        }

        public String getCls() {
            return cls;
        }
    }

    public AggregateDisplay aggregateDisplay(String aggregate, boolean hasContentReject,
            boolean hasPostAcceptContentError) {
        if (hasPostAcceptContentError) {
            return new AggregateDisplay("Có site báo sai nội dung sau khi áp dụng", "status-thu_hoi");
        }
        if (aggregate == null) {
            return new AggregateDisplay(aggregateStatusLabel(null), "status-da_ban_hanh");
        }
        switch (aggregate) {
            case "chua_phat_hanh":
                return new AggregateDisplay("Chưa phát hành", "status-da_ban_hanh");
            case "dang_cho":
                return new AggregateDisplay("Đang chờ phản hồi", "status-da_ban_hanh");
            case "mot_phan":
                return new AggregateDisplay("Một phần đã áp dụng", "status-da_ban_hanh");
            case "toan_bo":
                return new AggregateDisplay("Toàn bộ đã áp dụng", "status-hieu_luc");
            case "tranh_chap":
                return hasContentReject
                        ? new AggregateDisplay("Có site báo sai nội dung", "status-thu_hoi")
                        : new AggregateDisplay("Có site báo sai đăng ký", "status-cho_xac_nhan");
            default:
                return new AggregateDisplay(aggregateStatusLabel(aggregate), "status-da_ban_hanh");
        }
    }

    public AggregateDisplay aggregateDisplay(String aggregate) {
        return aggregateDisplay(aggregate, false, false);
    }
}
