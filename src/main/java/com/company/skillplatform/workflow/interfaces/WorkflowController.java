package com.company.skillplatform.workflow.interfaces;

import com.company.skillplatform.audit.application.DownloadAuditService;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.workflow.application.WorkflowService;
import com.company.skillplatform.workflow.domain.ReviewStatus;
import com.company.skillplatform.workflow.infrastructure.ArtifactDownloadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class WorkflowController {
 private final WorkflowService service;private final ArtifactDownloadService downloads;private final DownloadAuditService downloadAudit;
 public WorkflowController(WorkflowService s,ArtifactDownloadService d,DownloadAuditService da){service=s;downloads=d;downloadAudit=da;}
 @PostMapping("/skill-versions/{id}:submit-review") WorkflowService.SubmitView submit(@PathVariable Long id,@Valid@RequestBody VersionAction r,Authentication a,HttpServletRequest h){return service.submit(id,r.versionNo,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/skill-versions/{id}:push-to-company") WorkflowService.ReviewView pushToCompany(@PathVariable Long id,@RequestBody(required=false) PromotionRequest r,Authentication a,HttpServletRequest h){return service.pushToCompany(id,r==null?null:r.comment,r==null?null:r.wikiDocumentIds,(Long)a.getPrincipal(),h.getRequestId());}
 @GetMapping("/reviews") PageResponse<WorkflowService.ReviewView> reviews(@RequestParam(required=false)ReviewStatus status,@RequestParam(required=false)String keyword,@RequestParam(required=false,defaultValue="ALL")String scope,@RequestParam(required=false)Long teamId,Pageable pageable){return PageResponse.from(service.reviews(status,keyword,scope,teamId,pageable),v->v);}
 @GetMapping("/reviews/{id}") WorkflowService.ReviewView review(@PathVariable Long id){return service.review(id);}
 @PostMapping("/reviews/{id}:approve") WorkflowService.ReviewView approve(@PathVariable Long id,@Valid@RequestBody CommentRequest r,Authentication a,HttpServletRequest h){return service.approve(id,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/reviews/{id}:reject") WorkflowService.ReviewView reject(@PathVariable Long id,@Valid@RequestBody CommentRequest r,Authentication a,HttpServletRequest h){return service.reject(id,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/reviews:batch-approve") WorkflowService.BatchReviewView batchApprove(@Valid@RequestBody BatchReviewRequest r,Authentication a,HttpServletRequest h){return service.batchApprove(r.reviewIds,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/reviews:batch-reject") WorkflowService.BatchReviewView batchReject(@Valid@RequestBody BatchReviewRequest r,Authentication a,HttpServletRequest h){return service.batchReject(r.reviewIds,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/skill-versions/{id}:withdraw") SkillService.VersionView withdraw(@PathVariable Long id,@Valid@RequestBody VersionAction r,Authentication a,HttpServletRequest h){return service.withdraw(id,r.versionNo,r.comment,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping({"/skill-versions/{id}:publish","/skill-versions/{id}/publish"}) ResponseEntity<WorkflowService.BuildTaskView> publish(@PathVariable Long id,@RequestHeader("Idempotency-Key")String key,Authentication a,HttpServletRequest h){return ResponseEntity.accepted().body(service.publish(id,key,(Long)a.getPrincipal(),h.getRequestId()));}
 @GetMapping("/build-tasks/{id}") WorkflowService.BuildTaskView task(@PathVariable Long id){return service.task(id);}
 @GetMapping("/skill-versions/{id}/build-tasks") PageResponse<WorkflowService.BuildTaskView> tasks(@PathVariable Long id,Pageable pageable){return PageResponse.from(service.tasks(id,pageable),v->v);}
 @PostMapping("/skill-versions/{id}:deprecate") SkillService.VersionView deprecate(@PathVariable Long id,@RequestBody(required=false)DeprecateRequest r,Authentication a,HttpServletRequest h){return service.deprecate(id,r==null?null:r.replacementVersionId,(Long)a.getPrincipal(),h.getRequestId());}
 @PostMapping("/skill-versions/{id}:offline-impact") WorkflowService.ImpactView impact(@PathVariable Long id){return service.impact(id);}
 @PostMapping("/skill-versions/{id}:offline") SkillService.VersionView offline(@PathVariable Long id,@Valid@RequestBody OfflineRequest r,Authentication a,HttpServletRequest h){return service.offline(id,r.force,r.reason,(Long)a.getPrincipal(),h.getRequestId());}
 @GetMapping("/skill-versions/{id}/download") ResponseEntity<org.springframework.core.io.ByteArrayResource> download(@PathVariable Long id,@RequestParam(defaultValue="CODEBUDDY")String platform,@RequestParam(defaultValue="ANY")String osType,Authentication a){return downloads.download(id,platform,osType,(Long)a.getPrincipal());}
 public record VersionAction(@NotNull Integer versionNo,@NotBlank String comment){}public record CommentRequest(@NotBlank String comment){}public record PromotionRequest(@Size(max=50)java.util.List<@NotNull Long> wikiDocumentIds,@Size(max=2000)String comment){}public record BatchReviewRequest(@NotEmpty@Size(max=50)java.util.List<@NotNull Long> reviewIds,@Size(max=2000)String comment){}public record DeprecateRequest(Long replacementVersionId){}public record OfflineRequest(boolean force,@NotBlank String reason){}
}
