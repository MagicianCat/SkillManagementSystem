package com.company.skillplatform.user.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;
@Entity @Table(name="iam_scoped_role_assignment") public class ScopedRoleAssignmentEntity extends BaseJpaEntity {
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private IamUserEntity user;
 @Column(name="role_key",nullable=false,length=64) private String roleKey; @Column(name="scope_type",nullable=false,length=32) private String scopeType;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="team_id") private OrgTeamEntity team; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="granted_by",nullable=false) private IamUserEntity grantedBy;
 @Version @Column(name="version_no",nullable=false) private int versionNo; protected ScopedRoleAssignmentEntity(){}
 public ScopedRoleAssignmentEntity(IamUserEntity u,String role,String scope,OrgTeamEntity t,IamUserEntity g){user=u;roleKey=role;scopeType=scope;team=t;grantedBy=g;}
 public IamUserEntity getUser(){return user;} public String getRoleKey(){return roleKey;} public String getScopeType(){return scopeType;} public OrgTeamEntity getTeam(){return team;} public IamUserEntity getGrantedBy(){return grantedBy;} public int getVersionNo(){return versionNo;}
}
