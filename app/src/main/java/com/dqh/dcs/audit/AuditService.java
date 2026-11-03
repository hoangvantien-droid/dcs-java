package com.dqh.dcs.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * audit/AuditService — ghi vết append-only (port src/lib/audit.ts writeAudit,
 * DCS-3 §G, DCS-8 §3.4).
 *
 * QUY TẮC TUYỆT ĐỐI: mọi thao tác domain làm thay đổi trạng thái phải ghi vết
 * TRONG CÙNG MỘT GIAO DỊCH với thay đổi đó. writeAudit dùng CHUNG
 * NamedParameterJdbcTemplate (cùng DataSource) nên tự tham gia giao dịch
 * @Transactional đang mở của lời gọi service — không có biến thể chạy ngoài
 * giao dịch. Nếu ghi vết hỏng thì cả thay đổi lẫn ghi vết cùng rollback
 * (tái hiện writeAudit(tx,...) của TS; DCS-9 bẫy số 2).
 *
 * Bảng audit_log append-only ở tầng DB (rule chặn UPDATE/DELETE); tầng này chỉ INSERT.
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Ghi một bản ghi vết TRONG giao dịch hiện hành của lời gọi. Phải được gọi
     * bên trong một phương thức @Transactional của service (cùng giao dịch với
     * thay đổi), nếu không bản ghi vết sẽ tách khỏi thay đổi — đúng điều cấm.
     */
    public void writeAudit(AuditEntry entry) {
        // An toàn với giới hạn cột object_id (64 ký tự).
        String objectId = entry.getObjectId();
        if (objectId != null && objectId.length() > 64) {
            objectId = objectId.substring(0, 64);
        }
        ActorType actorType = entry.getActorType() != null ? entry.getActorType() : ActorType.nguoi;

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("actor_user_id", entry.getActorUserId())
                .addValue("actor_type", actorType.name())
                .addValue("event_type", entry.getEventType().name())
                .addValue("object_type", entry.getObjectType())
                .addValue("object_id", objectId)
                .addValue("before_state", toJson(entry.getBeforeState()))
                .addValue("after_state", toJson(entry.getAfterState()))
                .addValue("context", toJson(entry.getContext()));

        // Bind chuỗi JSON rồi CAST sang jsonb trong SQL — tránh phụ thuộc lớp
        // PGobject của driver ở compile-time (driver chỉ có ở scope runtime).
        jdbc.update(
                "INSERT INTO audit_log "
                + "(actor_user_id, actor_type, event_type, object_type, object_id, "
                + " before_state, after_state, context) "
                + "VALUES "
                + "(CAST(:actor_user_id AS uuid), :actor_type, :event_type, :object_type, :object_id, "
                + " CAST(:before_state AS jsonb), CAST(:after_state AS jsonb), CAST(:context AS jsonb))",
                p);
    }

    /** Serialize đối tượng Java sang chuỗi JSON; NULL khi không có dữ liệu. */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được JSONB cho audit_log", e);
        }
    }
}
