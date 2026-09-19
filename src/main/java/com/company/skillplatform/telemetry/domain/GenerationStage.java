package com.company.skillplatform.telemetry.domain;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.domain.DevelopmentStage;
import org.springframework.http.HttpStatus;

/**
 * Generation 的研发阶段。取值与 Skill 的 {@link DevelopmentStage} 一致（9 值），
 * 另加 {@link #MULTI_STAGE} 表示一次 Generation 跨多个非 GENERAL 阶段。
 */
public enum GenerationStage {
    REQUIREMENT, PRODUCT, ARCHITECTURE_DESIGN, UI_DESIGN, BACKEND_CODING, FRONTEND_CODING,
    SECURITY_REVIEW, TESTING, DEPLOYMENT,
    MULTI_STAGE;

    /** 从 Skill 研发阶段映射为 Generation 阶段；枚举同名直接对应。 */
    public static GenerationStage fromDevelopmentStage(DevelopmentStage stage) {
        if (stage == null) return null;
        return GenerationStage.valueOf(stage.name());
    }

    public static GenerationStage fromNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return GenerationStage.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_GENERATION_STAGE",
                    "Unknown generation stage: " + raw, HttpStatus.BAD_REQUEST);
        }
    }
}
