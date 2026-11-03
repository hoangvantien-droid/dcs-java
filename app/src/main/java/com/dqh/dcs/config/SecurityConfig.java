package com.dqh.dcs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * config/SecurityConfig — bean mã hoá mật khẩu argon2id thuần Java (QĐ-C).
 *
 * KHÔNG dùng Spring Security chain (QĐ-E/Lệch#1: tự viết filter phiên). Chỉ dùng
 * Argon2PasswordEncoder (BouncyCastle) để verify hash node-argon2id cũ và băm
 * mật khẩu mới. Tham số (m=65536,t=3,p=4 — mặc định node-argon2) đọc từ chuỗi
 * PHC khi verify, nên encoder round-trip được hash sinh bởi node-argon2.
 */
@Configuration
public class SecurityConfig {

    /** Khớp tham số mặc định node-argon2: type=argon2id, m=65536, t=3, p=4. */
    @Bean
    public Argon2PasswordEncoder passwordEncoder() {
        // saltLength=16, hashLength=32, parallelism=4, memory=65536 KiB, iterations=3.
        return new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    }
}
