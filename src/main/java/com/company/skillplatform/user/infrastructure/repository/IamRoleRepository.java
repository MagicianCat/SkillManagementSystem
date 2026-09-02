package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IamRoleRepository extends JpaRepository<IamRoleEntity, Long> {
    Optional<IamRoleEntity> findByRoleKey(String roleKey);
    List<IamRoleEntity> findAllByIdIn(Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update IamRoleEntity role set role.versionNo = role.versionNo + 1 where role.id = :roleId and role.versionNo = :versionNo")
    int incrementVersion(Long roleId, int versionNo);
}
