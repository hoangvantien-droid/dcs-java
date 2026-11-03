package com.dqh.dcs.functionalgroups;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.dqh.dcs.common.BusinessException;

/**
 * functionalgroups/FunctionalGroupService — nhóm chức năng (port
 * src/domain/functional-groups.ts, T-067, T-068).
 *
 * owner_user_id BẮT BUỘC, đích danh một người. Phát hiện nhóm lạc hậu
 * (v_functional_group_stale).
 */
@Service
public class FunctionalGroupService {

    private final NamedParameterJdbcTemplate jdbc;

    public FunctionalGroupService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FunctionalGroup(String groupId, String code, String name, String ownerUserId,
            int reviewCycleMonths, int memberCount) {
    }

    public record Member(String userId, String fullName, String email) {
    }

    public record StaleGroup(String groupId, String code, String name, String thanhVienCanRaSoat, String lyDo) {
    }

    public record CreateGroupInput(String code, String name, String ownerUserId) {
    }

    public List<FunctionalGroup> listGroups() {
        return jdbc.query(
                "SELECT fg.group_id, fg.code, fg.name, fg.owner_user_id, fg.review_cycle_months, "
                + "(SELECT count(*)::int FROM functional_group_members m WHERE m.group_id = fg.group_id) AS member_count "
                + "FROM functional_groups fg WHERE fg.is_active ORDER BY fg.code",
                new MapSqlParameterSource(),
                (rs, i) -> new FunctionalGroup(rs.getString("group_id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("owner_user_id"), rs.getInt("review_cycle_months"),
                        rs.getInt("member_count")));
    }

    public String createGroup(CreateGroupInput input) {
        if (input.ownerUserId() == null || input.ownerUserId().isEmpty()) {
            throw new BusinessException("fg.owner", "Nhóm chức năng bắt buộc có người phụ trách đích danh");
        }
        return jdbc.queryForObject(
                "INSERT INTO functional_groups (code, name, owner_user_id) "
                + "VALUES (:code, :name, CAST(:owner AS uuid)) RETURNING group_id",
                new MapSqlParameterSource().addValue("code", input.code()).addValue("name", input.name())
                        .addValue("owner", input.ownerUserId()), String.class);
    }

    public void addMember(String groupId, String userId, String addedBy) {
        jdbc.update(
                "INSERT INTO functional_group_members (group_id, user_id, added_by) "
                + "VALUES (CAST(:g AS uuid), CAST(:u AS uuid), CAST(:by AS uuid)) "
                + "ON CONFLICT (group_id, user_id) DO NOTHING",
                new MapSqlParameterSource().addValue("g", groupId).addValue("u", userId).addValue("by", addedBy));
    }

    public void removeMember(String groupId, String userId) {
        jdbc.update(
                "DELETE FROM functional_group_members WHERE group_id = CAST(:g AS uuid) AND user_id = CAST(:u AS uuid)",
                new MapSqlParameterSource().addValue("g", groupId).addValue("u", userId));
    }

    public List<Member> membersOf(String groupId) {
        return jdbc.query(
                "SELECT m.user_id, u.full_name, u.email FROM functional_group_members m "
                + "JOIN users u ON u.user_id = m.user_id WHERE m.group_id = CAST(:g AS uuid)",
                new MapSqlParameterSource("g", groupId),
                (rs, i) -> new Member(rs.getString("user_id"), rs.getString("full_name"), rs.getString("email")));
    }

    public List<StaleGroup> staleMembers() {
        return jdbc.query(
                "SELECT group_id, code, name, thanh_vien_can_ra_soat, ly_do "
                + "FROM v_functional_group_stale ORDER BY code",
                new MapSqlParameterSource(),
                (rs, i) -> new StaleGroup(rs.getString("group_id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("thanh_vien_can_ra_soat"), rs.getString("ly_do")));
    }
}
