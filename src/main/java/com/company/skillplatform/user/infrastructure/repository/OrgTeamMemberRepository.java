package com.company.skillplatform.user.infrastructure.repository;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrgTeamMemberRepository extends JpaRepository<OrgTeamMemberEntity,Long> {
    List<OrgTeamMemberEntity> findByTeamId(Long teamId);
    List<OrgTeamMemberEntity> findByUserId(Long userId);
    boolean existsByTeamIdAndUserId(Long teamId, Long userId);
    void deleteByTeamId(Long teamId);
}
