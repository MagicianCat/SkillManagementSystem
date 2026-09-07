package com.company.skillplatform.user.interfaces;
import com.company.skillplatform.user.application.FeishuDirectorySyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
@RestController @RequestMapping("/api/v1/admin/feishu")
public class FeishuDirectoryController {
 private final FeishuDirectorySyncService sync; public FeishuDirectoryController(FeishuDirectorySyncService s){sync=s;}
 @PostMapping("/sync") @PreAuthorize("hasAuthority('admin:identity')") ResponseEntity<SyncAccepted> sync(Authentication authentication){Long actorId=(Long)authentication.getPrincipal();sync.syncAsync(actorId);return ResponseEntity.accepted().body(new SyncAccepted("ACCEPTED","飞书通讯录同步任务已提交，请在通知中心查看结果"));}
 public record SyncAccepted(String status,String message){}
}
