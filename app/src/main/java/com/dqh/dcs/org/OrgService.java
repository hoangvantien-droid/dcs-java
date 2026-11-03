package com.dqh.dcs.org;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * org/OrgService — cây tổ chức DCS sở hữu (BU ↔ Site → Department)
 * (port src/domain/org.ts).
 *
 * Ver2 cho phép một Site phục vụ nhiều BU. org_site_bu là nguồn quan hệ chuẩn.
 */
@Service
public class OrgService {

    private final NamedParameterJdbcTemplate jdbc;

    public OrgService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Bu(String buId, String buCode, String name) {
    }

    public record Site(String siteId, String siteCode, String name, List<String> buIds) {
    }

    public record Dept(String deptId, String deptCode, String name, String siteId, String managerUserId) {
    }

    public List<Bu> listBu() {
        return jdbc.query("SELECT bu_id, bu_code, name FROM org_bu ORDER BY bu_code",
                new MapSqlParameterSource(),
                (rs, i) -> new Bu(rs.getString("bu_id"), rs.getString("bu_code"), rs.getString("name")));
    }

    public List<Site> listSites() {
        return jdbc.query(
                "SELECT s.site_id, s.site_code, s.name, "
                + "coalesce(array_agg(sb.bu_id ORDER BY sb.bu_id) FILTER (WHERE sb.bu_id IS NOT NULL), '{}') AS bu_ids "
                + "FROM org_site s LEFT JOIN org_site_bu sb ON sb.site_id = s.site_id "
                + "GROUP BY s.site_id, s.site_code, s.name ORDER BY s.site_code",
                new MapSqlParameterSource(),
                (rs, i) -> new Site(rs.getString("site_id"), rs.getString("site_code"), rs.getString("name"),
                        toList(rs.getArray("bu_ids"))));
    }

    public List<Dept> listDepartments() {
        return jdbc.query(
                "SELECT dept_id, dept_code, name, site_id, manager_user_id FROM org_department ORDER BY dept_code",
                new MapSqlParameterSource(),
                (rs, i) -> new Dept(rs.getString("dept_id"), rs.getString("dept_code"), rs.getString("name"),
                        rs.getString("site_id"), rs.getString("manager_user_id")));
    }

    public String createBu(String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO org_bu (bu_id, bu_code, name) VALUES (CAST(:id AS uuid), :code, :name)",
                new MapSqlParameterSource().addValue("id", id).addValue("code", code).addValue("name", name));
        return id;
    }

    /** Tạo Site và quan hệ BU đầu tiên; các BU bổ sung dùng addSiteBu. */
    @Transactional
    public String createSite(String code, String name, String initialBuId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO org_site (site_id, site_code, name, legacy_primary_bu_id) "
                + "VALUES (CAST(:id AS uuid), :code, :name, CAST(:bu AS uuid))",
                new MapSqlParameterSource().addValue("id", id).addValue("code", code)
                        .addValue("name", name).addValue("bu", initialBuId));
        jdbc.update("INSERT INTO org_site_bu (site_id, bu_id) VALUES (CAST(:id AS uuid), CAST(:bu AS uuid))",
                new MapSqlParameterSource().addValue("id", id).addValue("bu", initialBuId));
        return id;
    }

    public void addSiteBu(String siteId, String buId) {
        jdbc.update(
                "INSERT INTO org_site_bu (site_id, bu_id) VALUES (CAST(:site AS uuid), CAST(:bu AS uuid)) "
                + "ON CONFLICT (site_id, bu_id) DO NOTHING",
                new MapSqlParameterSource().addValue("site", siteId).addValue("bu", buId));
    }

    public String createDepartment(String code, String name, String siteId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO org_department (dept_id, dept_code, name, site_id) "
                + "VALUES (CAST(:id AS uuid), :code, :name, CAST(:site AS uuid))",
                new MapSqlParameterSource().addValue("id", id).addValue("code", code)
                        .addValue("name", name).addValue("site", siteId));
        return id;
    }

    private static List<String> toList(java.sql.Array arr) {
        if (arr == null) {
            return List.of();
        }
        try {
            Object a = arr.getArray();
            if (a instanceof Object[] objs) {
                return java.util.Arrays.stream(objs).map(String::valueOf).toList();
            }
            return List.of();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }
}
