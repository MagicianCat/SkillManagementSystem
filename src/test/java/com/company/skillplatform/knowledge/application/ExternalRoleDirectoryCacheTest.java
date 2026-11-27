package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalRoleDirectoryCacheTest {
    @Test
    void cachedAliasIsInvalidatedByChangesInTheLiveVisibleDirectory() {
        var directory=spy(new ExternalRoleDirectoryService(null,null));
        String scope="USER_VISIBLE";
        String alias="投监测试简称";
        var visible=new ExternalRoleDirectoryService.Name(26,"投监项目组","source-a","investment-monitoring",alias);
        doReturn(List.of(visible),List.of(visible),List.of())
                .when(directory).visibleNames(5,scope,null);

        var initial=directory.match(5,scope,null,alias,5,false);
        assertThat(initial.matchStatus()).isEqualTo("EXACT");
        assertThat(directory.match(5,scope,null,alias,5,false)).isSameAs(initial);
        assertThat(directory.match(5,scope,null,alias,5,false).matchStatus())
                .isEqualTo("NOT_FOUND_OR_NOT_VISIBLE");
    }
}
