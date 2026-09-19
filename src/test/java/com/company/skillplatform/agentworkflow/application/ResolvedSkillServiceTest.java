package com.company.skillplatform.agentworkflow.application;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class ResolvedSkillServiceTest {
 @Test void fixedRequiresVersion(){JdbcTemplate jdbc=mock(JdbcTemplate.class);Map<String,Object> row=new HashMap<>();row.put("skill_id",1L);row.put("skill_key","x");row.put("required",true);row.put("version_policy","FIXED");row.put("fixed_skill_version_id",null);row.put("latest_published_version_id",2L);when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(row));assertThrows(IllegalStateException.class,()->new ResolvedSkillService(jdbc,mock(ObjectStoragePort.class)).resolve(1));}
 @Test void unknownPolicyRejected(){JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("skill_id",1L,"skill_key","x","required",true,"version_policy","OTHER","latest_published_version_id",2L)));assertThrows(IllegalStateException.class,()->new ResolvedSkillService(jdbc,mock(ObjectStoragePort.class)).resolve(1));}
}
