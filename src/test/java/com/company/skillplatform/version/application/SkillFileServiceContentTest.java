package com.company.skillplatform.version.application;
import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
import com.company.skillplatform.common.application.BusinessException;import com.company.skillplatform.skill.application.SkillService;import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;import com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository;import com.company.skillplatform.storage.domain.ObjectStoragePort;import com.company.skillplatform.user.domain.IdentityProviderType;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;import com.company.skillplatform.version.domain.ChangeType;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import com.company.skillplatform.version.infrastructure.repository.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.ZipEntry;import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.*;import org.springframework.test.util.ReflectionTestUtils;

/** 验证 content() 在进程内工作区缺失（模拟服务重启）时能从对象存储回源。 */
class SkillFileServiceContentTest{
    SkillService skills=mock(SkillService.class);SkillFileIndexRepository indexes=mock(SkillFileIndexRepository.class);
    SkillSourceRevisionRepository revisions=mock(SkillSourceRevisionRepository.class);IamUserRepository users=mock(IamUserRepository.class);
    SkillOwnerRepository owners=mock(SkillOwnerRepository.class);ZipSecurityValidator validator=new ZipSecurityValidator();
    StandardConfigService configs=mock(StandardConfigService.class);ObjectStoragePort storage=mock(ObjectStoragePort.class);
    SkillFileService service;IamUserEntity user;SkillVersionEntity version;

    @BeforeEach void setup(){
        service=new SkillFileService(skills,indexes,revisions,users,owners,validator,configs,storage);
        user=new IamUserEntity(IdentityProviderType.MOCK,"u","u","h","U",null);ReflectionTestUtils.setField(user,"id",1L);
        SkillEntity skill=new SkillEntity("demo","Demo","d",null,user);
        version=new SkillVersionEntity(skill,null,ChangeType.INITIAL,user);ReflectionTestUtils.setField(version,"id",20L);
    }

    @Test void contentReloadsFromStorageWhenWorkspaceEmpty() throws IOException{
        version.storeSource("skills/demo/revisions/1/source.zip","a".repeat(64),100,Map.of("storageStatus","READY"));
        ReflectionTestUtils.setField(version,"sourceRevision",1);
        when(skills.version(20L)).thenReturn(version);
        when(storage.get("skills/demo/revisions/1/source.zip")).thenReturn(zip(Map.of("SKILL.md","# 你好".getBytes(StandardCharsets.UTF_8))));
        // 进程内 workspaces 为空（模拟重启后首次读取），应回源成功且中文不乱码
        assertThat(service.content(20L,"SKILL.md")).isEqualTo("# 你好");
    }

    @Test void contentFallsBackToBaseSourceForLegacyDraft() throws IOException{
        SkillVersionEntity base=new SkillVersionEntity(version.getSkill(),null,ChangeType.INITIAL,user);
        ReflectionTestUtils.setField(base,"id",50L);
        base.storeSource("skills/demo/revisions/1/source.zip","a".repeat(64),100,Map.of("storageStatus","READY"));
        ReflectionTestUtils.setField(base,"sourceRevision",1);
        ReflectionTestUtils.setField(version,"baseVersion",base);
        when(skills.version(20L)).thenReturn(version);
        when(storage.get("skills/demo/revisions/1/source.zip")).thenReturn(zip(Map.of("SKILL.md","# legacy".getBytes(StandardCharsets.UTF_8))));
        assertThat(service.content(20L,"SKILL.md")).isEqualTo("# legacy");
    }

    @Test void contentReturnsNotFoundForNeverUploadedDraft(){
        when(skills.version(20L)).thenReturn(version); // sourceObjectKey 仍为 pending
        assertThatThrownBy(()->service.content(20L,"SKILL.md")).isInstanceOf(BusinessException.class).hasMessageContaining("FILE_NOT_FOUND");
    }

    @Test void contentRejectsNonEditablePath(){
        when(skills.version(20L)).thenReturn(version);
        assertThatThrownBy(()->service.content(20L,"logo.png")).isInstanceOf(BusinessException.class).hasMessageContaining("FILE_NOT_EDITABLE");
    }

    private InputStream zip(Map<String,byte[]> files) throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(out);
        for(var e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}
        z.finish();return new ByteArrayInputStream(out.toByteArray());
    }
}
