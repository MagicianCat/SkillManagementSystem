package com.company.skillplatform.auth.infrastructure.repository;

import com.company.skillplatform.auth.infrastructure.entity.BrowserSessionEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface BrowserSessionRepository extends JpaRepository<BrowserSessionEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BrowserSessionEntity session join fetch session.user where session.sessionHash = :hash")
    Optional<BrowserSessionEntity> findBySessionHashForUpdate(String hash);
}
