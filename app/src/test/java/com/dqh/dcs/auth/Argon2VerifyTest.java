package com.dqh.dcs.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2VerifyTest — rủi ro cao #1 (QĐ-C): Argon2PasswordEncoder (BouncyCastle)
 * verify thành công một hash argon2id DO NODE-ARGON2 SINH.
 *
 * Hash dưới đây sinh bằng node-argon2 (type=argon2id, tham số mặc định
 * m=65536,t=3,p=4) từ repo nguồn, cho mật khẩu 'matkhau-test-123'. Encoder đọc
 * tham số từ chuỗi PHC khi verify nên round-trip được hash của node-argon2.
 * Không cần DB — test thuần.
 */
class Argon2VerifyTest {

    /** Hash thật từ node-argon2 cho mật khẩu 'matkhau-test-123'. */
    private static final String NODE_ARGON2_HASH =
            "$argon2id$v=19$m=65536,t=3,p=4$4k8gvr/5OOlb6w5+OziaIA$9LURYT9oGMaZXLtmjw4JQle28BQMYM4HqGJkbr0bqrs";

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);

    @Test
    void verifiesHashProducedByNodeArgon2() {
        assertTrue(encoder.matches("matkhau-test-123", NODE_ARGON2_HASH),
                "Argon2PasswordEncoder phải verify được hash argon2id của node-argon2");
    }

    @Test
    void rejectsWrongPassword() {
        assertFalse(encoder.matches("mat-khau-sai", NODE_ARGON2_HASH),
                "Mật khẩu sai phải bị từ chối");
    }

    @Test
    void roundTripsOwnEncoding() {
        String h = encoder.encode("mot-mat-khau-khac");
        assertTrue(encoder.matches("mot-mat-khau-khac", h));
        assertFalse(encoder.matches("khac-han", h));
    }
}
