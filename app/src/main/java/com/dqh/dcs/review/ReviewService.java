package com.dqh.dcs.review;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dqh.dcs.common.BusinessException;

/**
 * review/ReviewService — rà soát định kỳ (port src/domain/review.ts, T-075..077).
 *
 * Rà soát là bản ghi có vòng đời riêng. Ghi kết luận qua fn_record_review_verdict
 * (đường DUY NHẤT đặt lại next_review_date — QĐ-A, KHÔNG re-implement). Quyền
 * kết luận: người thuộc BỘ PHẬN SỞ HỮU tài liệu.
 */
@Service
public class ReviewService {

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String BACKLOG_COLS =
            "b.review_id, b.doc_id, b.version_no, b.title, b.due_date::text AS due_date, "
            + "b.status, b.verdict, b.verdict_note, b.department_name, b.so_ngay_qua_han, "
            + "d.scope_level, d.scope_bu_id, d.scope_site_id, d.scope_dept_id";

    private static ReviewRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ReviewRow(
                rs.getString("review_id"), rs.getString("doc_id"), rs.getString("version_no"),
                rs.getString("title"), rs.getString("due_date"), rs.getString("status"),
                rs.getString("verdict"), rs.getString("verdict_note"), rs.getString("department_name"),
                rs.getString("scope_level"), rs.getString("scope_bu_id"), rs.getString("scope_site_id"),
                rs.getString("scope_dept_id"), rs.getInt("so_ngay_qua_han"));
    }

    /** Tồn đọng rà soát — gồm cả nhánh can_sua đang treo (v_review_backlog). */
    public List<ReviewRow> reviewBacklog() {
        return jdbc.query(
                "SELECT " + BACKLOG_COLS + " FROM v_review_backlog b "
                + "JOIN documents d ON d.doc_id = b.doc_id ORDER BY b.so_ngay_qua_han DESC",
                new MapSqlParameterSource(), ReviewService::mapRow);
    }

    /** Tồn đọng rà soát của BỘ PHẬN SỞ HỮU mà người dùng thuộc về. */
    public List<ReviewRow> reviewBacklogForOwner(String userId) {
        return jdbc.query(
                "SELECT " + BACKLOG_COLS + " FROM v_review_backlog b "
                + "JOIN documents d ON d.doc_id = b.doc_id "
                + "JOIN periodic_reviews p ON p.review_id = b.review_id "
                + "JOIN document_versions v ON v.version_id = p.version_id "
                + "WHERE EXISTS (SELECT 1 FROM user_departments ud "
                + "  WHERE ud.user_id = CAST(:user_id AS uuid) AND ud.is_active "
                + "  AND ud.dept_id = v.owner_department_id AND v.owner_department_id IS NOT NULL) "
                + "ORDER BY b.so_ngay_qua_han DESC",
                new MapSqlParameterSource("user_id", userId), ReviewService::mapRow);
    }

    /** Tồn đọng rà soát của các SITE người dùng phụ trách (CHỈ ĐỌC để theo dõi). */
    public List<ReviewRow> reviewBacklogForSites(List<String> siteIds) {
        if (siteIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT " + BACKLOG_COLS + " FROM v_review_backlog b "
                + "JOIN documents d ON d.doc_id = b.doc_id "
                + "JOIN periodic_reviews p ON p.review_id = b.review_id "
                + "WHERE EXISTS (SELECT 1 FROM document_receipt r "
                + "  WHERE r.version_id = p.version_id AND r.site_id = ANY(CAST(:ids AS uuid[]))) "
                + "ORDER BY b.so_ngay_qua_han DESC",
                new MapSqlParameterSource("ids", "{" + String.join(",", siteIds) + "}"),
                ReviewService::mapRow);
    }

    /**
     * Ghi kết luận rà soát. giu_nguyen không cần note; can_sua/huy_bo cần giải
     * trình. Quyền thuộc bộ phận sở hữu. fn_record_review_verdict ép quy tắc và
     * là đường duy nhất sửa next_review_date.
     */
    @Transactional
    public void recordVerdict(String reviewId, String verdict, String note, String userId) {
        if (!"giu_nguyen".equals(verdict) && (note == null || note.trim().isEmpty())) {
            throw new BusinessException("review.note", "Kết luận này bắt buộc ghi giải trình");
        }
        List<Boolean> rows = jdbc.query(
                "SELECT EXISTS (SELECT 1 FROM periodic_reviews p "
                + "  JOIN document_versions v ON v.version_id = p.version_id "
                + "  JOIN user_departments ud ON ud.dept_id = v.owner_department_id "
                + "  WHERE p.review_id = CAST(:rid AS uuid) AND ud.user_id = CAST(:uid AS uuid) "
                + "  AND ud.is_active AND v.owner_department_id IS NOT NULL) AS ok "
                + "FROM periodic_reviews p WHERE p.review_id = CAST(:rid AS uuid) FOR UPDATE OF p",
                new MapSqlParameterSource().addValue("rid", reviewId).addValue("uid", userId),
                (rs, i) -> rs.getBoolean("ok"));
        if (rows.isEmpty()) {
            throw new BusinessException("review.not_found", "Không tìm thấy bản ghi rà soát");
        }
        if (!rows.get(0)) {
            throw new BusinessException("review.no_permission",
                    "Chỉ bộ phận sở hữu tài liệu được kết luận rà soát này", 403);
        }
        jdbc.update(
                "SELECT fn_record_review_verdict(CAST(:rid AS uuid), :verdict, :note, CAST(:uid AS uuid))",
                new MapSqlParameterSource().addValue("rid", reviewId).addValue("verdict", verdict)
                        .addValue("note", (note == null || note.isEmpty()) ? null : note)
                        .addValue("uid", userId));
    }
}
