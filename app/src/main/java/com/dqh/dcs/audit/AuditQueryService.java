package com.dqh.dcs.audit;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * audit/AuditQueryService — tra cứu ghi vết (port src/domain/audit-query.ts,
 * REQ-G01/G02/G03).
 *
 * Chỉ ĐỌC audit_log (append-only). Lọc theo đối tượng, loại sự kiện, khoảng thời gian.
 */
@Service
public class AuditQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditRow(String logId, String occurredAt, String actorUserId, String actorName,
            String actorType, String eventType, String objectType, String objectId) {
    }

    public record AuditFilter(String objectId, String eventType, String fromDate, String toDate, Integer limit) {
    }

    /** Danh sách loại sự kiện để lọc (từ CHECK audit_log.event_type). */
    public static final List<String> EVENT_TYPES = List.of(
            "nop_dang_ky", "rut_lai", "sua_truoc_khi_nop",
            "xac_nhan", "tu_choi_boi_admin", "tu_choi_tu_dong", "tich_checklist",
            "chuyen_hieu_luc", "chuyen_het_hieu_luc", "thu_hoi",
            "khai_nhan", "sua_nhan", "xac_nhan_nhan_chep",
            "xem", "tai", "in", "tra_cuu_qr", "xac_nhan_doc", "hoi_ai",
            "xem_so_sanh", "tim_kiem", "sua_ma_tran", "gui_thong_bao",
            "sinh_ra_soat", "ket_luan_ra_soat", "dong_ra_soat_theo_ban_moi",
            "tao_goi", "gan_vao_goi", "doi_ngay_goi", "thao_thanh_vien",
            "giai_the_goi", "kich_hoat_goi", "leo_thang",
            "sua_khan_cap", "kiem_toan_ven_tep", "tieu_huy_ho_so", "doi_so_hieu", "dong_bo_l1");

    public List<AuditRow> queryAudit(AuditFilter f) {
        int limit = Math.min(f.limit() == null ? 200 : f.limit(), 500);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("object_id", f.objectId())
                .addValue("event_type", f.eventType())
                .addValue("from_date", f.fromDate())
                .addValue("to_date", f.toDate())
                .addValue("lim", limit);
        return jdbc.query(
                "SELECT a.log_id::text AS log_id, "
                + "to_char(a.occurred_at, 'YYYY-MM-DD HH24:MI:SS') AS occurred_at, "
                + "a.actor_user_id, u.full_name AS actor_name, a.actor_type, "
                + "a.event_type, a.object_type, a.object_id "
                + "FROM audit_log a LEFT JOIN users u ON u.user_id = a.actor_user_id "
                + "WHERE (CAST(:object_id AS text) IS NULL OR a.object_id = :object_id) "
                + "AND (CAST(:event_type AS text) IS NULL OR a.event_type = :event_type) "
                + "AND (CAST(:from_date AS date) IS NULL OR a.occurred_at >= CAST(:from_date AS date)) "
                + "AND (CAST(:to_date AS date) IS NULL OR a.occurred_at < (CAST(:to_date AS date) + 1)) "
                + "ORDER BY a.occurred_at DESC LIMIT :lim",
                p,
                (rs, i) -> new AuditRow(rs.getString("log_id"), rs.getString("occurred_at"),
                        rs.getString("actor_user_id"), rs.getString("actor_name"), rs.getString("actor_type"),
                        rs.getString("event_type"), rs.getString("object_type"), rs.getString("object_id")));
    }
}
