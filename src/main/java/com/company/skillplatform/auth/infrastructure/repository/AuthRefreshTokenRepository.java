package com.company.skillplatform.auth.infrastructure.repository;

import com.company.skillplatform.auth.infrastructure.entity.AuthRefreshTokenEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshTokenEntity, Long> {
    Optional<AuthRefreshTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AuthRefreshTokenEntity token join fetch token.user where token.tokenHash = :tokenHash")
    Optional<AuthRefreshTokenEntity> findByTokenHashForUpdate(String tokenHash);
}
