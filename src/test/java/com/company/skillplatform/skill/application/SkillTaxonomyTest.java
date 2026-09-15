package com.company.skillplatform.skill.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.domain.DevelopmentStage;
import org.junit.jupiter.api.Test;

class SkillTaxonomyTest {
    @Test void mapsEveryFlowRootToStage(){assertThat(DevelopmentStage.fromRootCategoryKey("requirement")).isEqualTo(DevelopmentStage.REQUIREMENT);assertThat(DevelopmentStage.fromRootCategoryKey("product")).isEqualTo(DevelopmentStage.PRODUCT);assertThat(DevelopmentStage.fromRootCategoryKey("architecture")).isEqualTo(DevelopmentStage.ARCHITECTURE_DESIGN);assertThat(DevelopmentStage.fromRootCategoryKey("ui")).isEqualTo(DevelopmentStage.UI_DESIGN);assertThat(DevelopmentStage.fromRootCategoryKey("backend")).isEqualTo(DevelopmentStage.BACKEND_CODING);assertThat(DevelopmentStage.fromRootCategoryKey("frontend")).isEqualTo(DevelopmentStage.FRONTEND_CODING);assertThat(DevelopmentStage.fromRootCategoryKey("security")).isEqualTo(DevelopmentStage.SECURITY_REVIEW);assertThat(DevelopmentStage.fromRootCategoryKey("testing")).isEqualTo(DevelopmentStage.TESTING);assertThat(DevelopmentStage.fromRootCategoryKey("deployment")).isEqualTo(DevelopmentStage.DEPLOYMENT);}
    @Test void rejectsUnknownRoot(){assertThatThrownBy(()->DevelopmentStage.fromRootCategoryKey("other")).isInstanceOf(BusinessException.class);}
}
