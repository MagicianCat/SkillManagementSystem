package com.company.skillplatform.agent.application;

import com.company.skillplatform.knowledge.domain.ExternalRoleMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RetrievalScopeGuardTest {
    @Test void wikiScopeRequiresCurrentRoleCategoryAndResourceKind() {
        var guard=new RetrievalScopeGuard(mock(RetrievalPlanStore.class));
        var task=new RetrievalPlanner.Task("t1",26,"DOCUMENT","PROJECT_CONTEXT");
        var role=new ExternalRoleMetadata.Role(26,"投监","monitoring");
        var document=new ExternalRoleMetadata("external","doc","DOCUMENT",List.of(role));
        guard.requireWiki(task,document,"PROJECT_CONTEXT");
        assertThatThrownBy(()->guard.requireWiki(task,document,"SKILL_TOOL")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->guard.requireWiki(task,
                new ExternalRoleMetadata("external","tool","ASDM-TOOL",List.of(role)),"PROJECT_CONTEXT"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->guard.requireWiki(task,
                new ExternalRoleMetadata("external","doc","DOCUMENT",List.of()),"PROJECT_CONTEXT"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
