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
    public AgentOperationsDashboardService.Dashboard dashboard(@RequestParam(defaultValue = "PT15M") String window) {
        return service.snapshot(parseWindow(window));
    }

    private Duration parseWindow(String value) {
        if (value == null) return Duration.ofMinutes(15);
        try { Duration parsed=Duration.parse(value);if(parsed.isNegative()||parsed.isZero()||parsed.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("window out of range");return parsed; } catch (Exception ex) { throw new com.company.skillplatform.common.application.BusinessException("INVALID_MONITORING_WINDOW","Window must be ISO-8601 and between 1 minute and 1 day",org.springframework.http.HttpStatus.BAD_REQUEST,ex); }
    }
}
