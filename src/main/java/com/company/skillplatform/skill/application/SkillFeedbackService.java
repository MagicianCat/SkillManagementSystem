package com.company.skillplatform.skill.application;

import com.company.skillplatform.audit.application.AuditService;import com.company.skillplatform.audit.application.DownloadAuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.infrastructure.entity.*;
import com.company.skillplatform.skill.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillFeedbackService {
    private final SkillRepository skills; private final SkillService skillService; private final SkillFeedbackRepository feedback; private final IamUserRepository users; private final AuditService audit; private final DownloadAuditService downloads;
    public SkillFeedbackService(SkillRepository s, SkillService ss, SkillFeedbackRepository f, IamUserRepository u, AuditService a, DownloadAuditService d){skills=s;skillService=ss;feedback=f;users=u;audit=a;downloads=d;}
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly=true)
    public FeedbackPage list(String key, Long userId, Pageable pageable){
        skillService.assertVisible(key,userId); SkillEntity skill = skill(key); Page<SkillFeedbackEntity> page=feedback.findBySkillIdOrderByTimeCreatedDesc(skill.getId(),pageable);
        Double average=feedback.averageRating(skill.getId());
        FeedbackView mine=feedback.findBySkillIdAndUserId(skill.getId(),userId).map(this::view).orElse(null);
        return new FeedbackPage(average==null?0:average,feedback.countBySkillId(skill.getId()),downloads.count(skill.getId()),mine,page.map(this::view));
    }
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly=true)
    public FeedbackSummary summary(String key){skillService.assertVisible(key,currentUserId());SkillEntity skill=skill(key);Double average=feedback.averageRating(skill.getId());return new FeedbackSummary(average==null?0:average,feedback.countBySkillId(skill.getId()));}
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional
    public FeedbackView save(String key,Long userId,int rating,String comment,String requestId){
        if(rating<1||rating>5)throw error("INVALID_RATING","Rating must be between 1 and 5",HttpStatus.BAD_REQUEST);
        skillService.assertVisible(key,userId);SkillEntity skill=skill(key);IamUserEntity user=users.findById(userId).orElseThrow(()->error("USER_NOT_FOUND","User not found",HttpStatus.NOT_FOUND));
        SkillFeedbackEntity value=feedback.findBySkillIdAndUserId(skill.getId(),userId).orElseGet(()->new SkillFeedbackEntity(skill,user,rating,comment)); value.update(rating,comment); value=feedback.save(value);
        audit.success("SKILL_FEEDBACK_SAVED",user,"SKILL",skill.getId(),requestId,null,Map.of("rating",rating),Map.of()); return view(value);
    }
    @PreAuthorize("hasAuthority('skill:browse')") @Transactional
    public void delete(String key,Long userId,String requestId){skillService.assertVisible(key,userId);SkillEntity skill=skill(key);SkillFeedbackEntity value=feedback.findBySkillIdAndUserId(skill.getId(),userId).orElseThrow(()->error("FEEDBACK_NOT_FOUND","Feedback not found",HttpStatus.NOT_FOUND));feedback.delete(value);users.findById(userId).ifPresent(u->audit.success("SKILL_FEEDBACK_DELETED",u,"SKILL",skill.getId(),requestId,null,Map.of(),Map.of()));}
    private SkillEntity skill(String key){return skills.findBySkillKey(key).orElseThrow(()->error("SKILL_NOT_FOUND","Skill not found",HttpStatus.NOT_FOUND));}
    private Long currentUserId(){var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();return a==null||!(a.getPrincipal() instanceof Long)?null:(Long)a.getPrincipal();}
    private FeedbackView view(SkillFeedbackEntity f){return new FeedbackView(f.getId(),f.getUser().getId(),f.getUser().getDisplayName(),f.getRating(),f.getComment(),f.getTimeCreated(),f.getVersionNo());}
    private BusinessException error(String c,String m,HttpStatus s){return new BusinessException(c,m,s);}
    public record FeedbackSummary(double averageRating,long ratingCount){}
    public record FeedbackPage(double averageRating,long ratingCount,long downloadCount,FeedbackView mine,Page<FeedbackView> items){}
    public record FeedbackView(Long id,Long userId,String userName,int rating,String comment,java.time.Instant createdAt,int versionNo){}
}
