package com.company.skillplatform.user.infrastructure.repository;
import com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface OrgTeamMemberRepository extends JpaRepository<OrgTeamMemberEntity,Long> {
    @Query("select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId")
    List<OrgTeamMemberEntity> findByTeamId(Long teamId);
    @Query("select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId")
    List<OrgTeamMemberEntity> findByTeamIdWithUser(Long teamId);
    List<OrgTeamMemberEntity> findByTeamIdAndStatus(Long teamId, String status);
    @Query(value = "select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId and m.status = :status",
            countQuery = "select count(m) from OrgTeamMemberEntity m where m.team.id = :teamId and m.status = :status")
    Page<OrgTeamMemberEntity> findByTeamIdAndStatusWithUser(@Param("teamId") Long teamId,
                                                            @Param("status") String status,
                                                            Pageable pageable);
    @Query(value = "select m from OrgTeamMemberEntity m join fetch m.user where m.team.id = :teamId and m.status = :status and (lower(m.user.displayName) like lower(concat('%', :keyword, '%')) or lower(m.user.username) like lower(concat('%', :keyword, '%')))",
            countQuery = "select count(m) from OrgTeamMemberEntity m where m.team.id = :teamId and m.status = :status and (lower(m.user.displayName) like lower(concat('%', :keyword, '%')) or lower(m.user.username) like lower(concat('%', :keyword, '%')))")
    Page<OrgTeamMemberEntity> searchByTeamIdAndStatusWithUser(@Param("teamId") Long teamId,
                                                              @Param("status") String status,
                                                              @Param("keyword") String keyword,
                                                              Pageable pageable);
    List<OrgTeamMemberEntity> findByUserId(Long userId);
    boolean existsByTeamIdAndUserId(Long teamId, Long userId);
    void deleteByTeamId(Long teamId);
}
