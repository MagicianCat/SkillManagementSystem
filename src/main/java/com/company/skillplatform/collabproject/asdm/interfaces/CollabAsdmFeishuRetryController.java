package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retry endpoint for a Feishu publication that has not completed its publication gate. */
@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}/submissions/{submissionId}")
public class CollabAsdmFeishuRetryController {
    private final CollabProjectFlowService flow;

    public CollabAsdmFeishuRetryController(CollabProjectFlowService flow) { this.flow = flow; }

    @PostMapping("/feishu:retry")
    public CollabProjectFlowService.SubmissionView retry(@PathVariable String requirementKey,
            @PathVariable Long submissionId, Authentication auth) {
        return flow.retryFeishuPublication(requirementKey, submissionId, ((Number) auth.getPrincipal()).longValue());
    }
}
