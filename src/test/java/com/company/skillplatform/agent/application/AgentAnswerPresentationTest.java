package com.company.skillplatform.agent.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerPresentationTest {
    @Test void removesDisplayedIdentifiersAndKeepsCitationTargets() {
        String answer = AgentAnswerPresentation.clean("""
                FDE_WIKI：见《流程规范》（Wiki #256），documentId=256，externalRoleIds=[26, 58]。
                [查看原文](http://localhost/wiki?documentId=256&audience=PLATFORM)
                下载：`http://localhost/asdm-tools/download/13`
                请覆盖 3 个边界场景。
                """);
        assertThat(answer).contains("FDE 专区","请覆盖 3 个边界场景。",
                "[查看原文](http://localhost/wiki?documentId=256&audience=PLATFORM)",
                "[下载工具包](http://localhost/asdm-tools/download/13)")
                .doesNotContain("FDE_WIKI","Wiki #256","externalRoleIds");
        assertThat(AgentAnswerPresentation.clean(answer)).isEqualTo(answer);
    }
}
