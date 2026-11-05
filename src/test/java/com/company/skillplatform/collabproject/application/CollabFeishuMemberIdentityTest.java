package com.company.skillplatform.collabproject.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CollabFeishuMemberIdentityTest {
    @Test void directoryUserWithoutOpenIdUsesFeishuExternalId() {
        assertThat(CollabFeishuMemberIdentity.openId("FEISHU",null,"ou_15b71ad42fa50a843e6eff9ddd6e461e",
                "feishu_ou_15b71ad42fa50a843e6eff9ddd6e461e"))
                .isEqualTo("ou_15b71ad42fa50a843e6eff9ddd6e461e");
    }

    @Test void existingOpenIdWinsAndOtherProvidersCannotUseUsernameFallback() {
        assertThat(CollabFeishuMemberIdentity.openId("FEISHU","ou_current","ou_stale12345678",null))
                .isEqualTo("ou_current");
        assertThat(CollabFeishuMemberIdentity.openId("LOCAL",null,null,"feishu_ou_15b71ad42fa50a843e6eff9ddd6e461e"))
                .isNull();
    }
}
