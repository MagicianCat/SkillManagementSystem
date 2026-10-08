package com.company.skillplatform.git.application;

import com.company.skillplatform.git.domain.GitRemotePort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class GitWorkflowRepositoryFreezeServiceTest {
    @Test
    void freezesEveryActiveRepositoryAndPersistsImmutableIdentityAndSourceUri() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(31L))).thenReturn(List.of(
                Map.of("id", 9L, "normalized_url", "https://github.com/acme/api.git", "tracked_branch", "main"),
                Map.of("id", 10L, "normalized_url", "https://github.com/acme/web.git", "tracked_branch", "develop")));
        GitRemotePort remote = mock(GitRemotePort.class);
        when(remote.freeze("https://github.com/acme/api.git", "main")).thenReturn(frozen("api", "a"));
        when(remote.freeze("https://github.com/acme/web.git", "develop")).thenReturn(frozen("web", "b"));

        var result = new GitWorkflowRepositoryFreezeService(jdbc, remote).freeze(31L);

        assertThat(result).hasSize(2);
        verify(jdbc).update(startsWith("update workflow_run_git_repository set resolved_commit_sha"),
                eq("a".repeat(40)), eq("a".repeat(40)), eq("https://github.com/acme/api"),
                eq("file:///runtime/api.tar"), eq("a".repeat(64)), eq(9L), eq(31L));
        verify(jdbc).update(startsWith("update workflow_run_git_repository set resolved_commit_sha"),
                eq("b".repeat(40)), eq("b".repeat(40)), eq("https://github.com/acme/web"),
                eq("file:///runtime/web.tar"), eq("b".repeat(64)), eq(10L), eq(31L));
    }

    private static GitRemotePort.FrozenRepository frozen(String name, String marker) {
        return new GitRemotePort.FrozenRepository(name, "https://github.com/acme/" + name + ".git", "main",
                marker.repeat(40), marker.repeat(40), "https://github.com/acme/" + name,
                "file:///runtime/" + name + ".tar", marker.repeat(64));
    }
}
