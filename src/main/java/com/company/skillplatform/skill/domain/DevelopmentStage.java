package com.company.skillplatform.skill.domain;

import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;

/** 开发阶段，归属 Skill 本体，与版本 lifecycleStatus 无关。OTHER 兜底历史/未识别数据。 */
public enum DevelopmentStage {
    REQUIREMENT, DESIGN, FRONTEND_CODING, BACKEND_CODING, TESTING, RELEASED, OTHER;

    /** null/空串返回 null（语义：不指定/不修改）；非法值抛出 400 业务异常而非 IllegalArgumentException。 */
    public static DevelopmentStage fromNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return DevelopmentStage.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_DEVELOPMENT_STAGE",
                    "Unknown development stage: " + raw, HttpStatus.BAD_REQUEST);
        }
    }
}
