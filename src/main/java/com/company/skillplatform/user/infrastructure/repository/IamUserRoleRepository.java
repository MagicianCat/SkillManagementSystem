package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamUserRoleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IamUserRoleRepository extends JpaRepository<IamUserRoleEntity, Long> {
    List<IamUserRoleEntity> findAllByUserId(Long userId);
    @Modifying
    void deleteAllByUserId(Long userId);

    @Query("select distinct ur.role.roleKey from IamUserRoleEntity ur where ur.user.id = :userId and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE")
    List<String> findRoleKeysByUserId(Long userId);
}
