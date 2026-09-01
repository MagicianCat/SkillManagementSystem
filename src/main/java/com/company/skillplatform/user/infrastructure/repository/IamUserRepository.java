package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IamUserRepository extends JpaRepository<IamUserEntity, Long> {
    Optional<IamUserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
}
