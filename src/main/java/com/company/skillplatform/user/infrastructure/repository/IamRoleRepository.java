package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IamRoleRepository extends JpaRepository<IamRoleEntity, Long> {
    Optional<IamRoleEntity> findByRoleKey(String roleKey);
    List<IamRoleEntity> findAllByIdIn(Collection<Long> ids);
}
