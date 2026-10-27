package com.company.skillplatform.user.interfaces;
import com.company.skillplatform.user.application.FeishuDirectorySyncService;
import com.company.skillplatform.user.application.DefaultFeishuRoleBackfillJob;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/v1/admin/feishu")
public class FeishuDirectoryController {
 private final FeishuDirectorySyncService sync;
 private final DefaultFeishuRoleBackfillJob backfill;
 private final IamUserRoleRepository userRoles;
 public FeishuDirectoryController(FeishuDirectorySyncService s, DefaultFeishuRoleBackfillJob backfill, IamUserRoleRepository userRoles){sync=s;this.backfill=backfill;this.userRoles=userRoles;}
 @PostMapping("/sync") @PreAuthorize("hasAuthority('admin:identity')") ResponseEntity<SyncAccepted> sync(Authentication authentication){Long actorId=(Long)authentication.getPrincipal();sync.syncAsync(actorId);return ResponseEntity.accepted().body(new SyncAccepted("ACCEPTED","飞书通讯录同步任务已提交，请在通知中心查看结果"));}
 @PostMapping("/backfill-default-roles") @PreAuthorize("hasAuthority('admin:identity')") ResponseEntity<SyncAccepted> backfill(Authentication authentication){
  Long actorId=(Long)authentication.getPrincipal();
  if(!userRoles.findRoleKeysByUserId(actorId).contains("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅超级管理员可执行");
  if(!backfill.reserve())return ResponseEntity.status(HttpStatus.CONFLICT).body(new SyncAccepted("ALREADY_RUNNING","普通权限补齐任务正在运行"));
  try{backfill.execute(actorId);return ResponseEntity.accepted().body(new SyncAccepted("ACCEPTED","普通权限补齐任务已提交，完成后会发送站内通知"));}
  catch(TaskRejectedException failure){backfill.release();return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new SyncAccepted("QUEUE_FULL","后台任务繁忙，请稍后重试"));}
 }
 public record SyncAccepted(String status,String message){}
}
