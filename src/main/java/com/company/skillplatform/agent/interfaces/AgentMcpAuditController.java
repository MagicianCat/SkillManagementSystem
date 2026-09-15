package com.company.skillplatform.agent.interfaces;

import com.company.skillplatform.agent.application.AgentMcpAuditService;
import com.company.skillplatform.common.interfaces.PageResponse;
import jakarta.validation.constraints.Max;
import org.springframework.data.domain.Page;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/agent/mcp-audits")
public class AgentMcpAuditController {
    private final AgentMcpAuditService service;
    public AgentMcpAuditController(AgentMcpAuditService service) { this.service = service; }
    @GetMapping
    public PageResponse<AgentMcpAuditService.View> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") @Max(100) int size,
                                                @RequestParam(required = false) String runKey,
                                                @RequestParam(required = false) String sessionKey,
                                                @RequestParam(required = false) String toolName,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String sourceChannel,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to) {
        return PageResponse.from(service.list(page, size, runKey, sessionKey, toolName, status, sourceChannel, from, to), value -> value);
    }
}
