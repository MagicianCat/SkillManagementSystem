package com.company.skillplatform.user.infrastructure.repository;

import com.company.skillplatform.user.infrastructure.entity.IamRolePermissionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IamRolePermissionRepository extends JpaRepository<IamRolePermissionEntity, Long> {
    @Modifying
    void deleteAllByRoleId(Long roleId);
    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);
    List<IamRolePermissionEntity> findAllByRoleId(Long roleId);

    @Query("select distinct rp.permission.permissionKey from IamRolePermissionEntity rp join IamUserRoleEntity ur on ur.role.id = rp.role.id where ur.user.id = :userId and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE")
    List<String> findPermissionKeysByUserId(Long userId);

    @Query("select rp.permission.permissionKey from IamRolePermissionEntity rp where rp.role.id = :roleId")
    List<String> findPermissionKeysByRoleId(Long roleId);
}
