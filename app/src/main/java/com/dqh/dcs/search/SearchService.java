package com.dqh.dcs.search;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * search/SearchService — tìm kiếm thông thường + bộ lọc đa chiều
 * (port src/domain/search.ts, T-055/T-056).
 *
 * Gọi fn_search_limited của CSDL (QĐ-A). KHÔNG đọc embedding/index_status —
 * tìm kiếm phải chạy cả khi lớp AI ngừng (DCS-1 §6, REQ-K01). Lọc phạm vi/hiệu
 * lực/đa chiều là điều kiện trong fn_search, không lọc sau.
 */
@Service
public class SearchService {

    private final NamedParameterJdbcTemplate jdbc;

    public SearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static SearchHit mapHit(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SearchHit(
                rs.getString("doc_id"), rs.getString("version_id"), rs.getString("version_no"),
                rs.getString("title"), rs.getString("status"), rs.getString("section_code"),
                rs.getString("section_title"), rs.getString("snippet"), rs.getString("match_level"),
                rs.getDouble("rank"), rs.getString("scope_level"), rs.getString("effective_date"),
                rs.getString("next_review_date"), rs.getString("owner_department_id"),
                rs.getString("department_name"), rs.getString("origin"));
    }

    private static List<String> arrOrNull(List<String> a) {
        if (a == null) {
            return null;
        }
        List<String> clean = a.stream().map(x -> x == null ? "" : x.trim())
                .filter(x -> !x.isEmpty()).toList();
        return clean.isEmpty() ? null : clean;
    }

    private static String strOrNull(String s) {
        String v = s == null ? "" : s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String arrLiteral(List<String> a) {
        return a == null ? null : "{" + String.join(",", a) + "}";
    }

    /** Tìm kiếm đầy đủ (trang kết quả). Mặc định chỉ bản hiệu lực (REQ-K06). */
    public List<SearchHit> search(String userId, String q, boolean includeHistorical,
            SearchFilters filters, int limit) {
        if (q == null || q.trim().isEmpty()) {
            return List.of();
        }
        if (filters == null) {
            filters = SearchFilters.empty();
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("q", q)
                .addValue("historical", includeHistorical)
                .addValue("limit", limit)
                .addValue("doc_types", arrLiteral(arrOrNull(filters.docTypes())))
                .addValue("origin", strOrNull(filters.origin()))
                .addValue("scope_level", strOrNull(filters.scopeLevel()))
                .addValue("eff_from", strOrNull(filters.effFrom()))
                .addValue("eff_to", strOrNull(filters.effTo()))
                .addValue("match_levels", arrLiteral(arrOrNull(filters.matchLevels())))
                .addValue("sort", strOrNull(filters.sort()))
                .addValue("bu_id", strOrNull(filters.buId()))
                .addValue("site_id", strOrNull(filters.siteId()))
                .addValue("dept_id", strOrNull(filters.deptId()));
        return jdbc.query(
                "SELECT * FROM fn_search_limited("
                + "CAST(:user_id AS uuid), :q, :historical, :limit, "
                + "CAST(:doc_types AS text[]), CAST(:origin AS text), CAST(:scope_level AS text), "
                + "CAST(:eff_from AS date), CAST(:eff_to AS date), CAST(:match_levels AS text[]), CAST(:sort AS text), "
                + "CAST(:bu_id AS uuid), CAST(:site_id AS uuid), CAST(:dept_id AS uuid))",
                p, SearchService::mapHit);
    }

    /** Gợi ý nhanh cho ô tìm (sau 2 ký tự, tối đa 5). */
    public List<SearchHit> suggest(String userId, String q) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("user_id", userId).addValue("q", q);
        return jdbc.query(
                "SELECT * FROM fn_search_limited(CAST(:user_id AS uuid), :q, FALSE, 5)",
                p, SearchService::mapHit);
    }
}
