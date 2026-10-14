package com.company.skillplatform.collabproject.application;

/** Builds the exact prompt text displayed in stage handoff cards and sent by their actions. */
public final class CollabStagePromptFactory {
    private CollabStagePromptFactory() {}

    public static String context(String projectKey, String stageTitle) {
        return "请使用 Skill key `project-context-fetch` 获取项目 `" + projectKey
                + "` 中所有已批准的前序阶段产物，阅读并提炼与当前阶段相关的约束、决策和待办；"
                + "不要读取或依赖尚未批准的产物。当前阶段：" + stageTitle + "。";
    }

    public static String submit(String projectKey, String stageTitle, long stageId, int attemptNo,
                         boolean gitRequired) {
        String tick = String.valueOf((char) 96);
        return "请使用 Skill key " + tick + "project-artifact-submit" + tick + "，为项目 " + tick + projectKey
                + tick + " 的阶段 " + tick + stageTitle + tick + "（stageId=" + stageId + "，attemptNo=" + attemptNo
                + "）提交本阶段已完成的交付物。交付物在本地的地址为：{local file}。"
                + (gitRequired ? "该编码阶段还需同时提供 Git 仓库 URL 与 commit/PR URL。" : "");
    }
}
