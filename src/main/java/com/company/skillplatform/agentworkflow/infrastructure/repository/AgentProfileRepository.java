package com.company.skillplatform.agentworkflow.infrastructure.repository;
import com.company.skillplatform.agentworkflow.infrastructure.entity.AgentProfileEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentProfileRepository extends JpaRepository<AgentProfileEntity,Long>{Optional<AgentProfileEntity> findByCode(String code); List<AgentProfileEntity> findByProjectIdIsNullOrderByCode(); List<AgentProfileEntity> findByStatusOrderByCode(String status);}
