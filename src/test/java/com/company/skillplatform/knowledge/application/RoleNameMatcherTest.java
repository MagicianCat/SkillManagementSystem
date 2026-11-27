package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoleNameMatcherTest {
    @Test
    void matchesTypoInRoleContainingAsdm() {
        var result = RoleNameMatcher.search(
                List.of(role(1, "兼职ASDM开发角色", "source-a")), "兼职asdm开法角色", 5, false);
        assertThat(result.matchStatus()).isEqualTo("SUGGESTED_HIGH");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.canAutoConfirm()).isTrue();
            assertThat(candidate.editDistance()).isEqualTo(1);
        });
    }

    @Test
    void normalizesFullWidthAndCase() {
        var result = RoleNameMatcher.search(
                List.of(role(1, "兼职ASDM开发角色", "source-a")), "兼职ＡＳＤＭ开发角色", 5, false);
        assertThat(result.matchStatus()).isEqualTo("EXACT");
    }

    @Test
    void checksCompetingSourcesBeforeLimitingResults() {
        var result = RoleNameMatcher.search(
                List.of(role(1, "投监项目组", "source-a"), role(2, "投监项目组", "source-b")),
                "投监项目组", 1, false);
        assertThat(result.matchStatus()).isEqualTo("AMBIGUOUS");
        assertThat(result.candidates()).noneMatch(RoleNameMatcher.Candidate::canAutoConfirm);
    }

    @Test
    void detectsTransposition() {
        assertThat(RoleNameMatcher.distance("投监项组目", "投监项目组")).isEqualTo(1);
    }

    private ExternalRoleDirectoryService.Name role(long id, String name, String source) {
        return new ExternalRoleDirectoryService.Name(id, name, source, "role-" + id, name);
    }
}
