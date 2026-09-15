package com.company.skillplatform.telemetry.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.telemetry.application.SkillUsageAdminService;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/skill-usage")
@PreAuthorize("hasAuthority('admin:identity') or hasAuthority('admin:telemetry')")
public class SkillUsageAdminController {
    private final SkillUsageAdminService service;

    public SkillUsageAdminController(SkillUsageAdminService service) { this.service = service; }

    @GetMapping("/access-scope")
    public SkillUsageAdminService.AccessScope accessScope(Authentication authentication) {
        return service.accessScope(userId(authentication), global(authentication));
    }

    @GetMapping("/overview")
    public SkillUsageAdminService.Overview overview(@RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String skillKey, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        return service.overview(userId(authentication), global(authentication),
                new SkillUsageAdminService.Filters(from, to, teamId, skillKey, userId));
    }

    @GetMapping("/events")
    public PageResponse<SkillUsageAdminService.EventView> events(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String skillKey, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        SkillUsageAdminService.EventPage result = service.events(userId(authentication), global(authentication),
                new SkillUsageAdminService.Filters(from, to, teamId, skillKey, userId), page, size);
        return new PageResponse<>(result.items(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/events/{eventId}/conversation")
    public SkillUsageAdminService.ConversationView conversation(@PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size,
            Authentication authentication) {
        return service.conversation(userId(authentication), global(authentication), eventId, page, size);
    }

    private Long userId(Authentication authentication) { return (Long) authentication.getPrincipal(); }
    private boolean global(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> "admin:identity".equals(authority.getAuthority()));
    }
}
