package com.company.skillplatform.auth.infrastructure.repository;

import com.company.skillplatform.auth.infrastructure.entity.IdeAuthorizationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.time.Instant;

public interface IdeAuthorizationRepository extends JpaRepository<IdeAuthorizationEntity, Long> {
    Optional<IdeAuthorizationEntity> findByUserCodeHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from IdeAuthorizationEntity authorization where authorization.deviceCodeHash = :hash")
    Optional<IdeAuthorizationEntity> findByDeviceCodeHashForUpdate(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from IdeAuthorizationEntity authorization where authorization.userCodeHash = :hash")
    Optional<IdeAuthorizationEntity> findByUserCodeHashForUpdate(String hash);

    @Modifying
    @Query("delete from IdeAuthorizationEntity authorization where authorization.status in ('PENDING','APPROVED') and authorization.expiresAt <= :now")
    int deleteExpiredActive(Instant now);

    @Modifying
    @Query("delete from IdeAuthorizationEntity authorization where authorization.status in ('CONSUMED','DENIED') and authorization.timeUpdated <= :cutoff")
    int deleteOldTerminal(Instant cutoff);
}
