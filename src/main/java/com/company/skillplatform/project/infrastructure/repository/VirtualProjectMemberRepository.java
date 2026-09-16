package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.VirtualProjectMemberEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualProjectMemberRepository extends JpaRepository<VirtualProjectMemberEntity, Long> {
    List<VirtualProjectMemberEntity> findByProjectIdAndStatusOrderByTimeCreatedAsc(Long projectId, String status);
    List<VirtualProjectMemberEntity> findByUserIdAndStatus(Long userId, String status);
    Optional<VirtualProjectMemberEntity> findByProjectIdAndUserId(Long projectId, Long userId);
    boolean existsByProjectIdAndUserIdAndStatus(Long projectId, Long userId, String status);
    long countByProjectIdAndRoleKeyAndStatus(Long projectId, String roleKey, String status);
}
