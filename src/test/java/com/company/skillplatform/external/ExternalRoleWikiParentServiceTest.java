package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalRoleWikiParentServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ExternalRoleWikiParentService service=new ExternalRoleWikiParentService(db);

    @Test void rejectsParentFromAnotherRoleOrScope() {
        when(db.queryForObject(contains("FROM external_role WHERE"),eq(Integer.class),eq(7L))).thenReturn(1);
        when(db.query(anyString(),any(RowMapper.class),eq(7L),eq(203L),isNull())).thenReturn(List.of());
        assertThatThrownBy(()->service.requireEligible(7L,null,203L))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EXTERNAL_WIKI_PARENT_NOT_ELIGIBLE"));
        verify(db).query(contains("d.team_id <=> ?"),any(RowMapper.class),eq(7L),eq(203L),isNull());
    }

    @Test void inactiveRoleCannotListOrSelectParents() {
        when(db.queryForObject(contains("FROM external_role WHERE"),eq(Integer.class),eq(7L))).thenReturn(0);
        assertThatThrownBy(()->service.listForCard(7L,81))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EXTERNAL_ROLE_NOT_ACTIVE"));
        assertThatThrownBy(()->service.requireEligible(7L,null,203L))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EXTERNAL_ROLE_NOT_ACTIVE"));
    }
}
