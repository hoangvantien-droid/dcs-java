package com.dqh.dcs.audit;

/**
 * audit/ActorType — loại tác nhân, khớp CHECK trên audit_log.actor_type
 * (port src/lib/audit.ts ActorType). Giữ nguyên tiếng Việt không dấu.
 */
public enum ActorType {
    nguoi,
    he_thong,
    api;
}
