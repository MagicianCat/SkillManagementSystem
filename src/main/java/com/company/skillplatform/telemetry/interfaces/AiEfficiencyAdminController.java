package com.company.skillplatform.telemetry.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.telemetry.application.AiEfficiencyAdminService;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 研发效能看板的只读管理端 API；与现有 /overview 并存，新首页改用 /dashboard。 */
@RestController
@RequestMapping("/api/v1/admin/skill-usage")
@PreAuthorize("hasAuthority('admin:identity') or hasAuthority('admin:telemetry')")
public class AiEfficiencyAdminController {
    private final AiEfficiencyAdminService service;

    public AiEfficiencyAdminController(AiEfficiencyAdminService service) { this.service = service; }

    @GetMapping("/dashboard")
    public AiEfficiencyAdminService.Dashboard dashboard(@RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long userId, Authentication authentication) {
        return service.dashboard(userId(authentication), global(authentication),
                new AiEfficiencyAdminService.Filters(from, to, teamId, userId));
    }

    @GetMapping("/generations")
    public PageResponse<AiEfficiencyAdminService.GenerationRow> generations(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        AiEfficiencyAdminService.GenerationPage result = service.generations(userId(authentication),
                global(authentication), new AiEfficiencyAdminService.Filters(from, to, teamId, userId), page, size);
        return new PageResponse<>(result.items(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/stages/{stage}")
    public AiEfficiencyAdminService.StageDetail stage(@PathVariable String stage,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        return service.stage(userId(authentication), global(authentication), stage,
                new AiEfficiencyAdminService.Filters(from, to, teamId, userId));
    }

    @GetMapping("/teams/{teamId}")
    public AiEfficiencyAdminService.TeamDetail team(@PathVariable Long teamId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long userId, Authentication authentication) {
        return service.team(userId(authentication), global(authentication), teamId,
                new AiEfficiencyAdminService.Filters(from, to, null, userId));
    }

    @GetMapping("/projects/{projectKey}")
    public AiEfficiencyAdminService.ProjectDetail project(@PathVariable String projectKey,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        return service.project(userId(authentication), global(authentication), projectKey,
                new AiEfficiencyAdminService.Filters(from, to, teamId, userId));
    }

    @GetMapping("/skills/{skillKey}")
    public AiEfficiencyAdminService.SkillDetail skill(@PathVariable String skillKey,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long teamId, @RequestParam(required = false) Long userId,
            Authentication authentication) {
        return service.skill(userId(authentication), global(authentication), skillKey,
                new AiEfficiencyAdminService.Filters(from, to, teamId, userId));
    }

    private Long userId(Authentication authentication) { return (Long) authentication.getPrincipal(); }
    private boolean global(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> "admin:identity".equals(authority.getAuthority()));
    }
}
