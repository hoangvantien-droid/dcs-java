package com.dqh.dcs.auth;

/**
 * auth/UserRef — tham chiếu người dùng sau xác thực (port src/domain/auth.ts UserRef).
 */
public record UserRef(String userId, String email, String fullName, boolean active) {
}
