package com.dqh.dcs.users;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.dqh.dcs.auth.AuthService;
import com.dqh.dcs.common.BusinessException;

/**
 * users/UserService — quản trị người dùng do DCS sở hữu (port src/domain/users.ts).
 *
 * Một người có thể được gán nhiều Department; nghỉ việc thì tắt hoạt động để
 * bảo toàn bằng chứng xác nhận đọc và audit lịch sử.
 */
@Service
public class UserService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthService auth;

    public UserService(NamedParameterJdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public record UserRow(String userId, String email, String fullName, boolean active) {
    }

    public record CreateUserInput(String email, String fullName, String password) {
    }

    public List<UserRow> listUsers() {
        return jdbc.query(
                "SELECT user_id, email, full_name, is_active FROM users "
                + "WHERE email IS NOT NULL ORDER BY full_name NULLS LAST, email",
                new MapSqlParameterSource(),
                (rs, i) -> new UserRow(rs.getString("user_id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getBoolean("is_active")));
    }

    /** Tạo người dùng DCS với mật khẩu ban đầu. */
    public String createUser(CreateUserInput input) {
        if (input.password() == null || input.password().length() < 8) {
            throw new BusinessException("user.weak_password", "Mật khẩu tối thiểu 8 ký tự");
        }
        List<Integer> existing = jdbc.queryForList(
                "SELECT 1 FROM users WHERE lower(email) = lower(:email) LIMIT 1",
                new MapSqlParameterSource("email", input.email()), Integer.class);
        if (!existing.isEmpty()) {
            throw new BusinessException("user.email_taken", "Email " + input.email() + " đã tồn tại");
        }
        String id = UUID.randomUUID().toString();
        String hash = auth.hashPassword(input.password());
        jdbc.update(
                "INSERT INTO users (user_id, email, full_name, password_hash, is_active) "
                + "VALUES (CAST(:id AS uuid), :email, :full_name, :hash, TRUE)",
                new MapSqlParameterSource().addValue("id", id).addValue("email", input.email())
                        .addValue("full_name", input.fullName()).addValue("hash", hash));
        return id;
    }

    public void setUserActive(String userId, boolean active) {
        jdbc.update("UPDATE users SET is_active = :active WHERE user_id = CAST(:id AS uuid)",
                new MapSqlParameterSource().addValue("active", active).addValue("id", userId));
    }

    public void assignToDepartment(String userId, String deptId) {
        jdbc.update(
                "INSERT INTO user_departments (user_id, dept_id, is_active) "
                + "VALUES (CAST(:u AS uuid), CAST(:d AS uuid), TRUE) "
                + "ON CONFLICT (user_id, dept_id) DO UPDATE SET is_active = TRUE",
                new MapSqlParameterSource().addValue("u", userId).addValue("d", deptId));
    }

    public void removeFromDepartment(String userId, String deptId) {
        jdbc.update(
                "UPDATE user_departments SET is_active = FALSE "
                + "WHERE user_id = CAST(:u AS uuid) AND dept_id = CAST(:d AS uuid)",
                new MapSqlParameterSource().addValue("u", userId).addValue("d", deptId));
    }

    public List<String> departmentsOfUser(String userId) {
        return jdbc.queryForList(
                "SELECT dept_id FROM user_departments WHERE user_id = CAST(:u AS uuid) AND is_active",
                new MapSqlParameterSource("u", userId), String.class);
    }
}
