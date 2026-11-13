package com.company.skillplatform.telemetry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.skillplatform.skill.domain.DevelopmentStage;
import org.junit.jupiter.api.Test;

class GenerationStageTest {
    @Test void mapsEverySkillStageIncludingOther() {
        for (DevelopmentStage stage : DevelopmentStage.values())
            assertThat(GenerationStage.fromDevelopmentStage(stage).name()).isEqualTo(stage.name());
    }

    @Test void multiStageReflectsMultipleDistinctStages() {
        assertThat(GenerationStage.fromDevelopmentStage(DevelopmentStage.OTHER)).isEqualTo(GenerationStage.OTHER);
        assertThat(GenerationStage.MULTI_STAGE).isNotEqualTo(GenerationStage.OTHER);
    }
}