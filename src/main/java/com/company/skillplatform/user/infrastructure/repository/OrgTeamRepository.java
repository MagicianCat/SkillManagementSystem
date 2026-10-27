package com.company.skillplatform.user.infrastructure.repository;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgTeamRepository extends JpaRepository<OrgTeamEntity,Long>{
 Optional<OrgTeamEntity> findByProviderAndExternalDepartmentId(String provider,String externalId);
 List<OrgTeamEntity> findByStatusOrderByTeamNameAsc(String status);
 List<OrgTeamEntity> findByStatusAndProviderOrderByTeamNameAsc(String status,String provider);
 Page<OrgTeamEntity> findByStatusOrderByTeamNameAsc(String status,Pageable pageable);
 Page<OrgTeamEntity> findByStatusAndTeamNameContainingIgnoreCaseOrderByTeamNameAsc(String status,String keyword,Pageable pageable);
 List<OrgTeamEntity> findByProvider(String provider);
 List<OrgTeamEntity> findByProviderAndStatus(String provider,String status);
}
