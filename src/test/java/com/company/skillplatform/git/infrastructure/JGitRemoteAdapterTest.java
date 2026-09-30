package com.company.skillplatform.git.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.skillplatform.common.application.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class JGitRemoteAdapterTest {
    private GitProperties properties;
    private JGitRemoteAdapter adapter;

    @BeforeEach void setUp(){properties=new GitProperties();properties.setEnabled(true);properties.setUrlMode("BASE_RELATIVE");properties.setBaseUrl("https://github.com");properties.setAllowedHosts("github.com");adapter=new JGitRemoteAdapter(properties);}
    @Test void expandsBaseRelativeRepositoryWithoutCredentials(){assertEquals("https://github.com/MagicianCat/SkillManagementSystem.git",adapter.normalized("MagicianCat/SkillManagementSystem.git"));}
    @Test void rejectsCredentialsAndNonHttpsSchemes(){assertEquals("GIT_URL_FORBIDDEN",assertThrows(BusinessException.class,()->adapter.normalized("https://token@github.com/a/b.git")).getCode());assertEquals("GIT_URL_FORBIDDEN",assertThrows(BusinessException.class,()->adapter.normalized("git://github.com/a/b.git")).getCode());}
    @Test void rejectsLocalIpAndHostsOutsideAllowlist(){assertEquals("GIT_HOST_FORBIDDEN",assertThrows(BusinessException.class,()->adapter.normalized("https://127.0.0.1/a.git")).getCode());assertEquals("GIT_HOST_FORBIDDEN",assertThrows(BusinessException.class,()->adapter.normalized("https://gitlab.com/a/b.git")).getCode());}
    @Test void requiresExplicitAllowlist(){properties.setAllowedHosts("");assertEquals("GIT_HOST_FORBIDDEN",assertThrows(BusinessException.class,()->adapter.normalized("https://github.com/a/b.git")).getCode());}
    @Test @EnabledIfSystemProperty(named="git.live",matches="true") void resolvesPublicRepositoryAndFeatureBranch(){var result=adapter.resolve("https://github.com/MagicianCat/SkillManagementSystem.git");assertEquals("main",result.defaultBranch());org.junit.jupiter.api.Assertions.assertTrue(result.branches().contains("featura/sms"));org.junit.jupiter.api.Assertions.assertEquals(40,adapter.head(result.normalizedUrl(),"featura/sms").commitSha().length());}
}
