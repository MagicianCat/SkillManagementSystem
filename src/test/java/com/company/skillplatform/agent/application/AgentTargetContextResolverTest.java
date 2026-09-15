package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentTargetContextResolverTest {
    private final AgentTargetContextResolver resolver = new AgentTargetContextResolver();

    @Test
    void currentTurnExplicitTargetOverridesBothDimensions() {
        var result = resolver.resolve("这轮请改成 OpenCode + Windows，推荐测试 Skill");
        assertThat(result.platformSpecified()).isTrue();
        assertThat(result.platform()).isEqualTo("OPENCODE");
        assertThat(result.osTypeSpecified()).isTrue();
        assertThat(result.osType()).isEqualTo("WINDOWS");
    }

    @Test
    void unspecifiedDimensionKeepsExistingSessionValue() {
        var result = resolver.resolve("系统改成 macOS，平台保持不变");
        assertThat(result.platformSpecified()).isFalse();
        assertThat(result.osType()).isEqualTo("MACOS");
    }

    @Test
    void lastExplicitMentionWinsWithinCurrentTurn() {
        var result = resolver.resolve("原来想用 Windows，现在改用 Linux");
        assertThat(result.osType()).isEqualTo("LINUX");
    }

    @Test
    void explicitAnySystemIsPersisted() {
        var result = resolver.resolve("CodeBuddy，不限系统");
        assertThat(result.platform()).isEqualTo("CODEBUDDY");
        assertThat(result.osType()).isEqualTo("ANY");
    }
}
