package com.dqh.dcs.auth;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

import com.dqh.dcs.common.BusinessException;

/**
 * auth/AuthService — đăng nhập bằng email + mật khẩu, băm argon2id
 * (port src/domain/auth.ts, DCS-8 §4.1).
 *
 * Logic nghiệp vụ thuần, KHÔNG chạm HTTP (DCS-8 §2.1). verify dùng
 * Argon2PasswordEncoder (QĐ-C) — đọc tham số từ chuỗi PHC nên verify được hash
 * do node-argon2id cũ sinh.
 */
@Service
public class AuthService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Argon2PasswordEncoder encoder;

    public AuthService(NamedParameterJdbcTemplate jdbc, Argon2PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** Băm mật khẩu — dùng khi tạo/đổi mật khẩu. Không bao giờ lưu dạng rõ. */
    public String hashPassword(String plain) {
        return encoder.encode(plain);
    }

    /** So khớp mật khẩu với hash đã lưu (trả false khi hash null/sai định dạng). */
    public boolean verifyPassword(String hash, String plain) {
        if (hash == null) {
            return false;
        }
        try {
            return encoder.matches(plain, hash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Xác thực email + mật khẩu. Trả về UserRef nếu đúng, ném BusinessException
     * nếu sai. Thông báo lỗi cố ý mơ hồ (không nói "sai email" hay "sai mật
     * khẩu") để không lộ email nào tồn tại.
     */
    public UserRef authenticate(String email, String password) {
        List<Row> rows = jdbc.query(
                "SELECT user_id, email, full_name, is_active, password_hash "
                + "FROM users WHERE lower(email) = lower(:email) LIMIT 1",
                new MapSqlParameterSource("email", email),
                (rs, i) -> new Row(
                        rs.getString("user_id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getBoolean("is_active"),
                        rs.getString("password_hash")));

        BusinessException invalid = new BusinessException(
                "auth.invalid", "Email hoặc mật khẩu không đúng", 401);

        Row row = rows.isEmpty() ? null : rows.get(0);
        if (row == null || row.passwordHash() == null) {
            throw invalid;
        }
        if (!row.active()) {
            throw new BusinessException("auth.inactive", "Tài khoản đã ngừng hoạt động", 403);
        }
        if (!verifyPassword(row.passwordHash(), password)) {
            throw invalid;
        }
        return new UserRef(row.userId(), row.email(), row.fullName(), row.active());
    }

    private record Row(String userId, String email, String fullName, boolean active, String passwordHash) {
    }
}
