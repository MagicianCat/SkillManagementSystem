package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollabAsdmRuleController {
    private final CollabAsdmRuleService rules;
    public CollabAsdmRuleController(CollabAsdmRuleService rules) { this.rules = rules; }

    @GetMapping("/api/v1/collab-groups/{groupKey}/asdm/rules")
    public CollabAsdmRuleService.GroupRulesView getGroupRules(@PathVariable String groupKey, Authentication auth) {
        return rules.getGroupRules(groupKey, actor(auth));
    }

    @PutMapping("/api/v1/collab-groups/{groupKey}/asdm/rules")
    public CollabAsdmRuleService.GroupRulesView updateGroupRules(@PathVariable String groupKey,
            @Valid @RequestBody UpdateRulesRequest request, Authentication auth) {
        return rules.updateGroupRules(groupKey, actor(auth), request.expectedRevision(), request.stages());
    }

    @PostMapping("/api/v1/collab-groups/{groupKey}/asdm/rules:reset-stage")
    public CollabAsdmRuleService.GroupRulesView resetStage(@PathVariable String groupKey,
            @Valid @RequestBody ResetStageRequest request, Authentication auth) {
        return rules.resetStage(groupKey, request.stageKey(), actor(auth), request.expectedRevision());
    }

    @PostMapping("/api/v1/collab-groups/{groupKey}/asdm/rules:preview")
    public CollabAsdmRuleService.RulePreview preview(@PathVariable String groupKey,
            @Valid @RequestBody PreviewRulesRequest request, Authentication auth) {
        return rules.preview(groupKey, actor(auth), request.stageKey(), request.requirementKey(), request.overrides());
    }

    @GetMapping("/api/v1/collab-requirements/{requirementKey}/asdm/rules")
    public CollabAsdmRuleService.RequirementRulesView getRequirementRules(@PathVariable String requirementKey, Authentication auth) {
        return rules.getRequirementRules(requirementKey, actor(auth));
    }

    private long actor(Authentication auth) { return ((Number) auth.getPrincipal()).longValue(); }
    public record UpdateRulesRequest(@Min(0) int expectedRevision, @NotNull Map<String, CollabAsdmRuleService.StageOverride> stages) {}
    public record ResetStageRequest(@NotNull String stageKey, @Min(0) int expectedRevision) {}
    public record PreviewRulesRequest(@NotNull String stageKey, @NotNull String requirementKey, CollabAsdmRuleService.StageOverride overrides) {}
}
