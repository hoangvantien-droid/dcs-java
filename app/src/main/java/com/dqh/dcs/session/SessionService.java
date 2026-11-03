package com.dqh.dcs.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * session/SessionService — phiên làm việc lưu trong CSDL (port src/domain/session.ts,
 * DCS-8 §4.1).
 *
 * Phiên lưu DB chứ không trong bộ nhớ: 1 GB RAM không đủ cho kho phiên bộ nhớ,
 * và khởi động lại máy chủ không được làm mất phiên. Cookie chỉ mang session_id
 * đã ký; toàn bộ trạng thái phiên ở bảng app_session. TTL 8 giờ.
 */
@Service
public class SessionService {

    /** Hạn phiên: 8 giờ (DCS-8 §4.1). */
    public static final long SESSION_TTL_MS = 8L * 60 * 60 * 1000;

    private final NamedParameterJdbcTemplate jdbc;

    public SessionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tạo phiên mới cho người dùng, trả về session_id để đặt vào cookie. */
    public String createSession(String userId) {
        Timestamp expiresAt = Timestamp.from(Instant.now().plusMillis(SESSION_TTL_MS));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("user_id", userId)
                .addValue("expires_at", expiresAt);
        return jdbc.queryForObject(
                "INSERT INTO app_session (user_id, expires_at) "
                + "VALUES (:user_id, :expires_at) RETURNING session_id",
                p, String.class);
    }

    /**
     * Tra phiên còn hạn và trả về người dùng. Trả null nếu phiên không tồn tại,
     * hết hạn, hoặc người dùng đã ngừng hoạt động. Cập nhật last_seen_at.
     */
    public SessionUser resolveSession(String sessionId) {
        List<SessionUser> rows = jdbc.query(
                "UPDATE app_session s SET last_seen_at = now() "
                + "FROM users u "
                + "WHERE s.session_id = :session_id AND s.user_id = u.user_id "
                + "AND s.expires_at > now() AND u.is_active "
                + "RETURNING u.user_id, u.email, u.full_name",
                new MapSqlParameterSource("session_id", sessionId),
                (rs, i) -> new SessionUser(
                        rs.getString("user_id"),
                        rs.getString("email"),
                        rs.getString("full_name")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Huỷ một phiên (đăng xuất). */
    public void destroySession(String sessionId) {
        jdbc.update("DELETE FROM app_session WHERE session_id = :session_id",
                new MapSqlParameterSource("session_id", sessionId));
    }

    /** Dọn phiên hết hạn — gọi định kỳ bởi tác vụ nền. Trả về số phiên đã xoá. */
    public int purgeExpiredSessions() {
        return jdbc.update("DELETE FROM app_session WHERE expires_at <= now()",
                new MapSqlParameterSource());
    }
}
