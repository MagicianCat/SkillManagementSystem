package com.company.skillplatform.skill.domain;

import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;

/** 开发阶段由分类树根节点派生，不单独存储。 */
public enum DevelopmentStage {
    REQUIREMENT, PRODUCT, ARCHITECTURE_DESIGN, UI_DESIGN, BACKEND_CODING, FRONTEND_CODING,
    SECURITY_REVIEW, TESTING, DEPLOYMENT;

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

    public static DevelopmentStage fromRootCategoryKey(String key) {
        return switch (key) {
            case "requirement" -> REQUIREMENT;
            case "product" -> PRODUCT;
            case "architecture" -> ARCHITECTURE_DESIGN;
            case "ui" -> UI_DESIGN;
            case "backend" -> BACKEND_CODING;
            case "frontend" -> FRONTEND_CODING;
            case "security" -> SECURITY_REVIEW;
            case "testing" -> TESTING;
            case "deployment" -> DEPLOYMENT;
            default -> throw new BusinessException("INVALID_CATEGORY_TREE", "Unknown development flow root: " + key, HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }
}
