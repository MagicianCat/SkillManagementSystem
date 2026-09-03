package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamUserRoleEntity;
import java.util.List;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IamUserRoleRepository extends JpaRepository<IamUserRoleEntity, Long> {
    List<IamUserRoleEntity> findAllByUserId(Long userId);
    @Modifying
    void deleteAllByUserId(Long userId);

    @Query("select distinct ur.role.roleKey from IamUserRoleEntity ur where ur.user.id = :userId and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE")
    List<String> findRoleKeysByUserId(Long userId);

    @Query("select distinct ur.user from IamUserRoleEntity ur join IamRolePermissionEntity rp on rp.role.id = ur.role.id " +
            "where rp.permission.permissionKey = :permissionKey and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE " +
            "and ur.user.status = com.company.skillplatform.user.domain.UserStatus.ACTIVE")
    List<IamUserEntity> findActiveUsersByPermission(String permissionKey);
}
