package com.company.skillplatform.agentworkflow.application;

import java.util.*;

/** Deterministic transition policy; it never interprets natural language. */
public final class RequirementTransitionPolicy {
    private RequirementTransitionPolicy() {}
    public record Decision(String nextNode, boolean stageCompleted, boolean humanRequired, int loopCount) {}
    public static Decision apply(String node, String executionStatus, String resultCode, int loopCount, int maxLoop) {
        if (!"SUCCESS".equals(executionStatus)) return new Decision(null,false,true,loopCount);
        if ("reviewer".equals(node) && "APPROVED".equals(resultCode)) return new Decision(null,true,false,loopCount);
        if ("reviewer".equals(node) && "REVISION_REQUIRED".equals(resultCode)) {
            int next=loopCount+1; return next>maxLoop?new Decision(null,false,true,next):new Decision("writer",false,false,next);
        }
        return switch (node) { case "clarifier" -> new Decision("writer",false,false,loopCount); case "writer" -> new Decision("reviewer",false,false,loopCount); default -> new Decision(null,false,true,loopCount); };
    }
}
