package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.company.skillplatform.git.application.ProjectGitRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowCodeGraphServiceTest {
    @Test
    void activatesWithoutBuildingWhenFrozenCommitsAreStillFresh() {
        var fixture = fixture();
        var activated = run("RUNNING");
        doReturn(List.of("READY_TO_START")).when(fixture.jdbc).query(anyString(), any(RowMapper.class), eq(31L));
        when(fixture.frozen.isFresh(31L)).thenReturn(true);
        when(fixture.workflows.activatePrepared(31L, 7L)).thenReturn(activated);

        assertThat(fixture.service.activate(31L, 7L)).isSameAs(activated);

        verify(fixture.workflows).requireRunManager(31L, 7L);
        verify(fixture.preparation, never()).prepare(anyLong(), any());
    }

    /**
     * M6 §20 contract: the semantic sidecar status never gates activation.
     * Only the structural gate (workflow_run.status = READY_TO_START) matters.
     * DEGRADED, INDEXING, DISABLED and null semantic statuses must all reach
     * {@code activatePrepared} unchanged when the frozen repositories are fresh.
     */
    @Test
    void activatesRegardlessOfSemanticIndexStatus() {
        for (String semanticStatus : new String[]{"READY", "DEGRADED", "INDEXING", "DISABLED"}) {
            var fixture = fixture();
            var activated = run("RUNNING");
            doReturn(List.of("READY_TO_START")).when(fixture.jdbc).query(
                    eq("SELECT status FROM workflow_run WHERE id=?"), any(RowMapper.class), eq(31L));
            doReturn(List.of(semanticStatus)).when(fixture.jdbc).query(
                    eq("SELECT semantic_index_status FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='ACTIVE' LIMIT 1"),
                    any(RowMapper.class), eq(31L));
            when(fixture.frozen.isFresh(31L)).thenReturn(true);
            when(fixture.workflows.activatePrepared(31L, 7L)).thenReturn(activated);

            assertThat(fixture.service.activate(31L, 7L))
                    .as("semantic_index_status=%s must not block activation", semanticStatus)
                    .isSameAs(activated);
            verify(fixture.workflows).activatePrepared(31L, 7L);
            verify(fixture.preparation, never()).prepare(anyLong(), any());
        }
    }

    @Test
    void activatesWhenSemanticBindingRowIsMissing() {
        // Defensive: when no ACTIVE binding row exists yet (rare but possible during
        // racing binding activation), activation must still proceed.
        var fixture = fixture();
        var activated = run("RUNNING");
        doReturn(List.of("READY_TO_START")).when(fixture.jdbc).query(
                eq("SELECT status FROM workflow_run WHERE id=?"), any(RowMapper.class), eq(31L));
        doReturn(List.<String>of()).when(fixture.jdbc).query(
                eq("SELECT semantic_index_status FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='ACTIVE' LIMIT 1"),
                any(RowMapper.class), eq(31L));
        when(fixture.frozen.isFresh(31L)).thenReturn(true);
        when(fixture.workflows.activatePrepared(31L, 7L)).thenReturn(activated);

        assertThat(fixture.service.activate(31L, 7L)).isSameAs(activated);
        verify(fixture.workflows).activatePrepared(31L, 7L);
    }

    @Test
    void rebuildsAndDoesNotCreateAgentStagesWhenHeadChangedBeforeActivation() {
        var fixture = fixture();
        var current = run("PREPARING_CODE_GRAPH");
        doReturn(List.of("READY_TO_START")).when(fixture.jdbc).query(anyString(), any(RowMapper.class), eq(31L));
        when(fixture.frozen.isFresh(31L)).thenReturn(false);
        when(fixture.frozen.inputs(31L)).thenReturn(List.of(new RepositoryInput("repo", "repo-1",
                "a".repeat(40), "b".repeat(40), "file:///inputs/repo.tar", "c".repeat(64))));
        when(fixture.workflows.run(31L, 7L)).thenReturn(current);

        assertThat(fixture.service.activate(31L, 7L)).isSameAs(current);

        verify(fixture.frozen).freeze(31L);
        verify(fixture.preparation).prepare(eq(7L), argThat(command -> "START_FRESHNESS_REFRESH".equals(command.reason())));
        verify(fixture.workflows, never()).activatePrepared(anyLong(), anyLong());
    }

    private static Fixture fixture() {
        var workflows = mock(AgentWorkflowService.class);
        var projects = mock(ProjectGitRepositoryService.class);
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        var preparation = mock(CodeGraphPreparationService.class);
        var status = mock(CodeGraphStatusService.class);
        var jdbc = mock(JdbcTemplate.class);
        var properties = new CodeGraphWorkflowProperties("GITNEXUS", "1.6.12", "0.1.0", "cfg", "group");
        return new Fixture(new WorkflowCodeGraphService(workflows, projects, frozen, preparation, status, properties, jdbc),
                workflows, frozen, preparation, jdbc);
    }

    private static AgentWorkflowService.RunView run(String status) {
        return new AgentWorkflowService.RunView(31L, 4L, "FULL_DESIGN", 1, status, "requirement",
                null, null, null, null, true, List.of(), List.of());
    }

    private record Fixture(WorkflowCodeGraphService service, AgentWorkflowService workflows,
                           GitWorkflowRepositoryFreezeService frozen, CodeGraphPreparationService preparation,
                           JdbcTemplate jdbc) {}
}
