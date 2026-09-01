package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamPermissionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IamPermissionRepository extends JpaRepository<IamPermissionEntity, Long> {
    Optional<IamPermissionEntity> findByPermissionKey(String permissionKey);
    List<IamPermissionEntity> findAllByIdIn(Collection<Long> ids);
}
