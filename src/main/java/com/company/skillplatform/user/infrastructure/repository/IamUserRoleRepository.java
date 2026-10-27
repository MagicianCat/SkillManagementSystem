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
    @Query(value = "INSERT INTO iam_user_role (user_id, role_id, created_by, time_created, time_updated) " +
            "SELECT u.id, r.id, u.id, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6) " +
            "FROM iam_user u JOIN iam_role r ON r.role_key = 'CONSUMER' AND r.status = 'ACTIVE' " +
            "WHERE u.identity_provider = 'FEISHU' AND u.status = 'ACTIVE' " +
            "AND NOT EXISTS (SELECT 1 FROM iam_user_role existing JOIN iam_role er ON er.id = existing.role_id " +
            "JOIN iam_role_permission rp ON rp.role_id = er.id WHERE existing.user_id = u.id AND er.status = 'ACTIVE') " +
            "AND NOT EXISTS (SELECT 1 FROM iam_user_role consumer WHERE consumer.user_id = u.id AND consumer.role_id = r.id)", nativeQuery = true)
    int grantConsumerToUnassignedFeishuUsers();
    @Modifying
    void deleteAllByUserId(Long userId);

    @Query("select distinct ur.role.roleKey from IamUserRoleEntity ur where ur.user.id = :userId and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE")
    List<String> findRoleKeysByUserId(Long userId);

    @Query("select distinct ur.user from IamUserRoleEntity ur join IamRolePermissionEntity rp on rp.role.id = ur.role.id " +
            "where rp.permission.permissionKey = :permissionKey and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE " +
            "and ur.user.status = com.company.skillplatform.user.domain.UserStatus.ACTIVE")
    List<IamUserEntity> findActiveUsersByPermission(String permissionKey);

    @Query("select distinct ur.user from IamUserRoleEntity ur where ur.role.roleKey = 'ADMIN' " +
            "and ur.role.status = com.company.skillplatform.user.domain.RoleStatus.ACTIVE " +
            "and ur.user.status = com.company.skillplatform.user.domain.UserStatus.ACTIVE")
    List<IamUserEntity> findActiveSuperAdmins();
}
