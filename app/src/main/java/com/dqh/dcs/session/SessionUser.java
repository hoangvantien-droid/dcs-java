package com.dqh.dcs.session;

/**
 * session/SessionUser — người dùng gắn với một phiên (port src/domain/session.ts).
 */
public record SessionUser(String userId, String email, String fullName) {
}
