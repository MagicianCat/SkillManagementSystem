package com.company.skillplatform.user.infrastructure.repository;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface OrgTeamMemberRepository extends JpaRepository<OrgTeamMemberEntity,Long> {
    @Query("select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId")
    List<OrgTeamMemberEntity> findByTeamId(Long teamId);
    @Query("select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId")
    List<OrgTeamMemberEntity> findByTeamIdWithUser(Long teamId);
    List<OrgTeamMemberEntity> findByTeamIdAndStatus(Long teamId, String status);
    List<OrgTeamMemberEntity> findByUserId(Long userId);
    boolean existsByTeamIdAndUserId(Long teamId, Long userId);
    void deleteByTeamId(Long teamId);
}
