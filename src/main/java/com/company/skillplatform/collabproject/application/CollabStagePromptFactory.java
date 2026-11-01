package com.company.skillplatform.collabproject.application;

/** Builds prompts with the requirement UUID and CLI stage key required by both Skills. */
public final class CollabStagePromptFactory {
    private CollabStagePromptFactory() {}

    public static String context(String requirementKey, String stageKey, String stageTitle) {
        return "请使用 Skill key `project-context-fetch`，以需求 UUID `" + requirementKey
                + "` 和阶段 key `" + stageKey + "` 获取所有已批准的前序阶段产物。"
                + "当前阶段是「" + stageTitle + "」。请阅读并提炼相关约束、决策和待办，不要依赖未批准的产物。";
    }

    public static String submit(String requirementKey, String stageKey, String stageTitle, long stageId, int attemptNo,
                                boolean gitRequired) {
        return "请使用 Skill key `project-artifact-submit`，以需求 UUID `" + requirementKey
                + "` 和阶段 key `" + stageKey + "` 提交「" + stageTitle + "」阶段交付物。"
                + "交付物本地地址：{local file}。stageId=" + stageId + "、attemptNo=" + attemptNo
                + " 仅供核对，由 Skill 从平台获取，不要作为命令参数。"
                + (gitRequired ? "该编码阶段还需提供 Git 仓库 URL 与 commit/PR URL。" : "");
    }
}
