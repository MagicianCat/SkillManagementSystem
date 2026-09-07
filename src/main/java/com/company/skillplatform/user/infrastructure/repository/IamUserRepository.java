package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IamUserRepository extends JpaRepository<IamUserEntity, Long>, JpaSpecificationExecutor<IamUserEntity> {
    Optional<IamUserEntity> findByUsername(String username);
    Optional<IamUserEntity> findByIdentityProviderAndExternalUserId(com.company.skillplatform.user.domain.IdentityProviderType provider, String externalUserId);
    Optional<IamUserEntity> findByFeishuOpenId(String openId);
    Optional<IamUserEntity> findByFeishuUserId(String userId);
    boolean existsByUsername(String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update IamUserEntity user set user.versionNo = user.versionNo + 1 where user.id = :userId and user.versionNo = :versionNo")
    int incrementVersion(Long userId, int versionNo);
}
