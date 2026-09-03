package com.company.skillplatform.notification.infrastructure.repository;

import com.company.skillplatform.notification.infrastructure.entity.UserNotificationEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    Page<UserNotificationEntity> findByRecipientId(Long recipientId, Pageable pageable);
    Page<UserNotificationEntity> findByRecipientIdAndReadAtIsNull(Long recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    Optional<UserNotificationEntity> findByIdAndRecipientId(Long id, Long recipientId);
}

