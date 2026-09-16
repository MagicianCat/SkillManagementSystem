package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.VirtualProjectEntity;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualProjectRepository extends JpaRepository<VirtualProjectEntity, Long> {
    Optional<VirtualProjectEntity> findByProjectKey(String projectKey);
    Page<VirtualProjectEntity> findByStatusOrderByTimeUpdatedDesc(String status, Pageable pageable);
}
