package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.common.application.BusinessException;
import org.junit.jupiter.api.Test;

class AgentRequestTargetTest {
    @Test void recommendationAlwaysUsesValidatedRunTarget(){
        var target=AgentRequestTarget.fromRun("codebuddy","macos");
        assertThat(target.platform()).isEqualTo("CODEBUDDY");
        assertThat(target.osType()).isEqualTo("MACOS");
    }
    @Test void rejectsUnknownPlatformAndOperatingSystem(){
        assertThatThrownBy(()->AgentRequestTarget.fromRun("other","MACOS")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->AgentRequestTarget.fromRun("CODEBUDDY","SOLARIS")).isInstanceOf(BusinessException.class);
    }
}
