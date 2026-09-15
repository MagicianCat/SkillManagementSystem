package com.company.skillplatform.auth.infrastructure.repository;

import com.company.skillplatform.auth.infrastructure.entity.FeishuUserDocumentCredentialEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface FeishuUserDocumentCredentialRepository extends JpaRepository<FeishuUserDocumentCredentialEntity, Long> {
    Optional<FeishuUserDocumentCredentialEntity> findByUserId(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from FeishuUserDocumentCredentialEntity credential join fetch credential.user where credential.user.id = :userId")
    Optional<FeishuUserDocumentCredentialEntity> findByUserIdForUpdate(Long userId);
}
