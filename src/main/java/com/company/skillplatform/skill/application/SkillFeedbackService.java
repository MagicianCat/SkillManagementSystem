package com.company.skillplatform.skill.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.audit.application.DownloadAuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.infrastructure.entity.*;
import com.company.skillplatform.skill.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillFeedbackService {
    private final SkillRepository skills;
    private final SkillService skillService;
    private final SkillRatingRepository ratings;
    private final SkillCommentRepository comments;
    private final IamUserRepository users;
    private final AuditService audit;
    private final DownloadAuditService downloads;

    public SkillFeedbackService(SkillRepository skills, SkillService skillService, SkillRatingRepository ratings,
                                SkillCommentRepository comments, IamUserRepository users, AuditService audit,
                                DownloadAuditService downloads) {
        this.skills=skills; this.skillService=skillService; this.ratings=ratings; this.comments=comments;
        this.users=users; this.audit=audit; this.downloads=downloads;
    }

    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly=true)
    public FeedbackPage list(String key, Long userId, Pageable pageable) {
        skillService.assertVisible(key,userId); SkillEntity skill=skill(key);
        Double average=ratings.averageRating(skill.getId());
        RatingView mine=ratings.findBySkillIdAndUserId(skill.getId(),userId).map(this::ratingView).orElse(null);
        Page<CommentView> page=comments.findBySkillIdOrderByTimeCreatedDesc(skill.getId(),pageable).map(this::commentView);
        return new FeedbackPage(average==null?0:average,ratings.countBySkillId(skill.getId()),downloads.count(skill.getId()),mine,page);
    }

    @PreAuthorize("hasAuthority('skill:browse')") @Transactional(readOnly=true)
    public FeedbackSummary summary(String key) { skillService.assertVisible(key,null); SkillEntity skill=skill(key); Double average=ratings.averageRating(skill.getId()); return new FeedbackSummary(average==null?0:average,ratings.countBySkillId(skill.getId())); }

    @PreAuthorize("hasAuthority('skill:browse')") @Transactional
    public RatingView saveRating(String key, Long userId, int rating, String requestId) {
        if(rating<1||rating>5) throw error("INVALID_RATING","Rating must be between 1 and 5",HttpStatus.BAD_REQUEST);
        skillService.assertVisible(key,userId); SkillEntity skill=skill(key); IamUserEntity user=user(userId);
        SkillRatingEntity value=ratings.findBySkillIdAndUserId(skill.getId(),userId).orElseGet(()->new SkillRatingEntity(skill,user,rating)); value.update(rating); value=ratings.save(value);
        audit.success("SKILL_RATING_SAVED",user,"SKILL",skill.getId(),requestId,null,Map.of("rating",rating),Map.of()); return ratingView(value);
    }

    @PreAuthorize("hasAuthority('skill:browse')") @Transactional
    public CommentView addComment(String key, Long userId, String comment, String requestId) {
        if(comment==null||comment.trim().isEmpty()) throw error("COMMENT_REQUIRED","Comment must not be blank",HttpStatus.BAD_REQUEST);
        String value=comment.trim(); if(value.length()>2000) throw error("COMMENT_TOO_LONG","Comment must not exceed 2000 characters",HttpStatus.BAD_REQUEST);
        skillService.assertVisible(key,userId); SkillEntity skill=skill(key); IamUserEntity user=user(userId);
        SkillCommentEntity saved=comments.save(new SkillCommentEntity(skill,user,value));
        audit.success("SKILL_COMMENT_CREATED",user,"SKILL",skill.getId(),requestId,null,Map.of("commentId",saved.getId()),Map.of()); return commentView(saved);
    }

    @PreAuthorize("hasAuthority('skill:browse')") @Transactional
    public void deleteComment(String key, Long userId, Long commentId, String requestId) {
        skillService.assertVisible(key,userId); SkillEntity skill=skill(key); SkillCommentEntity value=comments.findByIdAndSkillId(commentId,skill.getId()).orElseThrow(()->error("COMMENT_NOT_FOUND","Comment not found",HttpStatus.NOT_FOUND));
        if(!Objects.equals(value.getUser().getId(),userId)) throw error("COMMENT_OWNER_REQUIRED","Only the comment author may delete it",HttpStatus.FORBIDDEN);
        comments.delete(value); audit.success("SKILL_COMMENT_DELETED",user(userId),"SKILL",skill.getId(),requestId,null,Map.of("commentId",commentId),Map.of());
    }

    private SkillEntity skill(String key){return skills.findBySkillKey(key).orElseThrow(()->error("SKILL_NOT_FOUND","Skill not found",HttpStatus.NOT_FOUND));}
    private IamUserEntity user(Long id){return users.findById(id).orElseThrow(()->error("USER_NOT_FOUND","User not found",HttpStatus.NOT_FOUND));}
    private RatingView ratingView(SkillRatingEntity value){return new RatingView(value.getId(),value.getRating(),value.getTimeUpdated(),value.getVersionNo());}
    private CommentView commentView(SkillCommentEntity value){return new CommentView(value.getId(),value.getUser().getId(),value.getUser().getDisplayName(),value.getComment(),value.getTimeCreated(),value.getVersionNo());}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
    public record FeedbackSummary(double averageRating,long ratingCount){}
    public record FeedbackPage(double averageRating,long ratingCount,long downloadCount,RatingView myRating,Page<CommentView> comments){}
    public record RatingView(Long id,int rating,Instant updatedAt,int versionNo){}
    public record CommentView(Long id,Long userId,String userName,String comment,Instant createdAt,int versionNo){}
}
