package com.company.skillplatform.notification.infrastructure.repository;

import com.company.skillplatform.notification.infrastructure.entity.UserNotificationEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    @Query(value = """
            select n from UserNotificationEntity n
            where n.recipient.id = :recipientId
            order by case when n.readAt is null then 0 else 1 end,
                     n.timeCreated desc,
                     n.id desc
            """,
            countQuery = "select count(n) from UserNotificationEntity n where n.recipient.id = :recipientId")
    Page<UserNotificationEntity> findInbox(@Param("recipientId") Long recipientId, Pageable pageable);

    @Query(value = """
            select n from UserNotificationEntity n
            where n.recipient.id = :recipientId
              and n.readAt is null
            order by n.timeCreated desc, n.id desc
            """,
            countQuery = "select count(n) from UserNotificationEntity n where n.recipient.id = :recipientId and n.readAt is null")
    Page<UserNotificationEntity> findUnreadInbox(@Param("recipientId") Long recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    Optional<UserNotificationEntity> findByIdAndRecipientId(Long id, Long recipientId);
}
