package com.dqh.dcs.audit;

/**
 * audit/AuditEntry — một bản ghi vết để INSERT vào audit_log
 * (port src/lib/audit.ts AuditEntry).
 *
 * beforeState/afterState/context lưu JSONB; truyền Object (sẽ serialize JSON),
 * NULL khi không có. actorType mặc định 'nguoi'; actorUserId NULL khi tác nhân
 * là hệ thống.
 */
public final class AuditEntry {

    private EventType eventType;
    private String objectType;
    /** Khoá của đối tượng; audit_log.object_id giới hạn 64 ký tự. */
    private String objectId;
    private ActorType actorType = ActorType.nguoi;
    /** NULL khi tác nhân là hệ thống. */
    private String actorUserId;
    private Object beforeState;
    private Object afterState;
    /** ip, thiết bị, phiên; với hoi_ai: chunk_ids_used. */
    private Object context;

    public AuditEntry() {
    }

    public static AuditEntry of(EventType eventType, String objectType, String objectId) {
        AuditEntry e = new AuditEntry();
        e.eventType = eventType;
        e.objectType = objectType;
        e.objectId = objectId;
        return e;
    }

    public EventType getEventType() {
        return eventType;
    }

    public AuditEntry eventType(EventType v) {
        this.eventType = v;
        return this;
    }

    public String getObjectType() {
        return objectType;
    }

    public AuditEntry objectType(String v) {
        this.objectType = v;
        return this;
    }

    public String getObjectId() {
        return objectId;
    }

    public AuditEntry objectId(String v) {
        this.objectId = v;
        return this;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public AuditEntry actorType(ActorType v) {
        this.actorType = v;
        return this;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public AuditEntry actorUserId(String v) {
        this.actorUserId = v;
        return this;
    }

    public Object getBeforeState() {
        return beforeState;
    }

    public AuditEntry beforeState(Object v) {
        this.beforeState = v;
        return this;
    }

    public Object getAfterState() {
        return afterState;
    }

    public AuditEntry afterState(Object v) {
        this.afterState = v;
        return this;
    }

    public Object getContext() {
        return context;
    }

    public AuditEntry context(Object v) {
        this.context = v;
        return this;
    }
}
