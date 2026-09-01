package com.company.skillplatform.auth.infrastructure.repository;

import com.company.skillplatform.auth.infrastructure.entity.AuthRefreshTokenEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshTokenEntity, Long> {
    Optional<AuthRefreshTokenEntity> findByTokenHash(String tokenHash);
}
