package com.company.skillplatform.user.infrastructure.repository;
import com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ScopedRoleAssignmentRepository extends JpaRepository<ScopedRoleAssignmentEntity,Long>{
 List<ScopedRoleAssignmentEntity> findByUserId(Long userId);
 List<ScopedRoleAssignmentEntity> findByUserIdIn(Collection<Long> userIds);
 List<ScopedRoleAssignmentEntity> findByTeamId(Long teamId);
 Optional<ScopedRoleAssignmentEntity> findByUserIdAndRoleKeyAndScopeTypeAndTeamId(Long userId,String roleKey,String scopeType,Long teamId);
 boolean existsByUserIdAndRoleKeyAndScopeType(Long userId,String roleKey,String scopeType);
 void deleteByUserIdAndRoleKeyAndScopeTypeAndTeamId(Long userId,String roleKey,String scopeType,Long teamId);
}
