package com.company.skillplatform.agent.interfaces;

import com.company.skillplatform.agent.application.AgentOperationsDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/agent/operations")
public class AgentOperationsDashboardController {
    private final AgentOperationsDashboardService service;
    public AgentOperationsDashboardController(AgentOperationsDashboardService service) { this.service = service; }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('admin:audit')")
    public AgentOperationsDashboardService.Dashboard dashboard(@RequestParam(defaultValue = "15m") String window) {
        return service.snapshot(parseWindow(window));
    }

    private Duration parseWindow(String value) {
        if (value == null) return Duration.ofMinutes(15);
        try { return Duration.parse(value); } catch (Exception ignored) { return Duration.ofMinutes(15); }
    }
}
