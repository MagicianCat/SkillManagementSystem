package com.company.skillplatform.collabproject.interfaces;

import com.company.skillplatform.collabproject.application.CollabApprovalFeedbackService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Read-only API for collecting evidence around one requirement approval round. */
@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}/approval-feedback")
public class CollabApprovalFeedbackController {
    private final CollabApprovalFeedbackService feedback;
    public CollabApprovalFeedbackController(CollabApprovalFeedbackService feedback) { this.feedback = feedback; }

    @GetMapping("/context")
    public Map<String,Object> context(@PathVariable String requirementKey,
            @RequestParam(defaultValue="REQUIREMENT") String stageKey, Authentication auth) {
        return feedback.context(requirementKey, stageKey, actor(auth));
    }
    @GetMapping("/messages")
    public Map<String,Object> messages(@PathVariable String requirementKey, @RequestParam Long submissionId,
            @RequestParam String cutoffAt, @RequestParam(required=false) String cursor, Authentication auth) {
        return feedback.messages(requirementKey, submissionId, cutoffAt, cursor, actor(auth));
    }
    @GetMapping("/comments")
    public Map<String,Object> comments(@PathVariable String requirementKey, @RequestParam Long submissionId,
            @RequestParam String cutoffAt, @RequestParam(required=false) String cursor, Authentication auth) {
        return feedback.comments(requirementKey, submissionId, cutoffAt, cursor, actor(auth));
    }
    @GetMapping("/artifacts")
    public Map<String,Object> artifacts(@PathVariable String requirementKey, @RequestParam Long submissionId, Authentication auth) {
        return feedback.artifacts(requirementKey, submissionId, actor(auth));
    }
    @GetMapping("/decisions")
    public Map<String,Object> decisions(@PathVariable String requirementKey, @RequestParam Long submissionId, Authentication auth) {
        return feedback.decisions(requirementKey, submissionId, actor(auth));
    }
    private Long actor(Authentication auth) { return (Long) auth.getPrincipal(); }
}
