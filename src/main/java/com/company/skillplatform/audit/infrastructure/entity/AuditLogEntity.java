package com.company.skillplatform.audit.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity extends BaseJpaEntity {
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private IamUserEntity actor;
    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;
    @Column(name = "target_id")
    private Long targetId;
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;
    @Column(nullable = false, length = 32)
    private String result;
    @Column(length = 1024)
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "json")
    private Map<String, Object> before;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "json")
    private Map<String, Object> after;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "json")
    private Map<String, Object> metadata;

    protected AuditLogEntity() {}

    public AuditLogEntity(String eventType, IamUserEntity actor, String targetType, Long targetId,
                          String requestId, Map<String, Object> before, Map<String, Object> after,
                          Map<String, Object> metadata) {
        this.eventType = eventType;
        this.actor = actor;
        this.targetType = targetType;
        this.targetId = targetId;
        this.requestId = requestId;
        this.result = "SUCCESS";
        this.before = before;
        this.after = after;
        this.metadata = metadata;
    }

    public String getEventType() { return eventType; }
    public Long getTargetId() { return targetId; }
    public String getRequestId() { return requestId; }
}
