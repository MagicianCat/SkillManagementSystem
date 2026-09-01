package com.company.skillplatform.common.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "time_created", nullable = false, updatable = false)
    private Instant timeCreated;

    @LastModifiedDate
    @Column(name = "time_updated", nullable = false)
    private Instant timeUpdated;

    public Long getId() { return id; }
    public Instant getTimeCreated() { return timeCreated; }
    public Instant getTimeUpdated() { return timeUpdated; }
}
