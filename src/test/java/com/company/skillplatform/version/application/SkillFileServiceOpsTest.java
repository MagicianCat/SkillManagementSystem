package com.company.skillplatform.version.application;
import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
import com.company.skillplatform.common.application.BusinessException;import com.company.skillplatform.skill.application.SkillService;import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;import com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository;import com.company.skillplatform.storage.domain.ObjectStoragePort;import com.company.skillplatform.user.domain.IdentityProviderType;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;import com.company.skillplatform.version.domain.ChangeType;import com.company.skillplatform.version.infrastructure.entity.SkillFileIndexEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import com.company.skillplatform.version.infrastructure.repository.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.ZipEntry;import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.*;import org.springframework.mock.web.MockMultipartFile;import org.springframework.test.util.ReflectionTestUtils;

/** 覆盖 SkillFileService 的工作区/上传/编辑/差异等主要路径。 */
class SkillFileServiceOpsTest{
    SkillService skills=mock(SkillService.class);SkillFileIndexRepository indexes=mock(SkillFileIndexRepository.class);
    SkillSourceRevisionRepository revisions=mock(SkillSourceRevisionRepository.class);IamUserRepository users=mock(IamUserRepository.class);
    SkillOwnerRepository owners=mock(SkillOwnerRepository.class);ZipSecurityValidator validator=new ZipSecurityValidator();
    StandardConfigService configs=mock(StandardConfigService.class);ObjectStoragePort storage=mock(ObjectStoragePort.class);
    SkillFileService service;IamUserEntity user;SkillEntity skill;SkillVersionEntity draft;

    @BeforeEach void setup(){
        service=new SkillFileService(skills,indexes,revisions,users,owners,validator,configs,storage);
        user=new IamUserEntity(IdentityProviderType.MOCK,"u","u","h","U",null);ReflectionTestUtils.setField(user,"id",1L);
        skill=new SkillEntity("demo","Demo","d",null,user);
        draft=new SkillVersionEntity(skill,null,ChangeType.INITIAL,user);ReflectionTestUtils.setField(draft,"id",20L);
        when(skills.version(20L)).thenReturn(draft);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(configs.generated("demo")).thenReturn(Map.of("skill.yaml","kind: skill".getBytes(StandardCharsets.UTF_8)));
        when(revisions.save(any())).thenAnswer(i->{var r=(com.company.skillplatform.version.infrastructure.entity.SkillSourceRevisionEntity)i.getArgument(0);ReflectionTestUtils.setField(r,"id",99L);return r;});
        when(indexes.saveAll(any())).thenAnswer(i->i.getArgument(0));
        when(skills.openDraft(eq("demo"),anyLong(),anyString())).thenAnswer(i->new SkillService.VersionView(20L,"demo",ChangeType.INITIAL,draft.getLifecycleStatus(),null,null,0,0,null,null,null));
    }

    @Test void uploadStoresZipAndBuildsIndex() throws IOException{
        MockMultipartFile file=new MockMultipartFile("file","src.zip","application/zip",zipBytes(Map.of("demo/SKILL.md","# 你好".getBytes(StandardCharsets.UTF_8))));
        var view=service.upload("demo",file,"init",1L,"r");
        assertThat(view.versionId()).isEqualTo(20L);assertThat(view.sourceRevision()).isEqualTo(1);
        assertThat(view.files()).extracting(SkillFileService.FileView::path).contains("SKILL.md","skill.yaml");
        verify(storage).put(eq("skills/demo/revisions/1/source.zip"),any(),anyLong(),eq("application/zip"));
        assertThat(draft.getSourceObjectKey()).isEqualTo("skills/demo/revisions/1/source.zip");
        assertThat(service.content(20L,"SKILL.md")).isEqualTo("# 你好");
    }

    @Test void uploadRejectsEmptyZip(){assertThatThrownBy(()->service.upload("demo",new MockMultipartFile("f",new byte[0]),"c",1L,"r")).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("ZIP_REQUIRED"));}

    @Test void uploadRejectsInvalidZip(){MockMultipartFile file=new MockMultipartFile("f","x".getBytes());assertThatThrownBy(()->service.upload("demo",file,"c",1L,"r")).isInstanceOf(BusinessException.class);}

    @Test void filesListsIndexedFiles(){SkillFileIndexEntity row=new SkillFileIndexEntity(draft,0,"SKILL.md","file","text/markdown",10,true,"UPLOADED");when(indexes.findByVersionIdAndSourceRevision(20L,0)).thenReturn(List.of(row));assertThat(service.files(20L)).hasSize(1);assertThat(row.getSourceRevision()).isEqualTo(0);}

    @Test void saveContentEditsAndPersists(){
        seedWorkspace(Map.of("SKILL.md","v1"));
        var files=service.saveContent(20L,"SKILL.md","v2",1L);
        assertThat(files).extracting(SkillFileService.FileView::path).contains("SKILL.md");
        assertThat(service.content(20L,"SKILL.md")).isEqualTo("v2");
        assertThat(draft.getSourceRevision()).isEqualTo(1);
    }

    @Test void saveContentWithMatchingVersionNo(){seedWorkspace(Map.of("SKILL.md","v1"));assertThat(service.saveContent(20L,"SKILL.md","v2",0,1L)).isNotEmpty();}

    @Test void saveContentWithStaleVersionNoConflicts(){assertThatThrownBy(()->service.saveContent(20L,"SKILL.md","v2",7,1L)).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT"));}

    @Test void saveContentRejectsNonEditable(){seedWorkspace(Map.of("SKILL.md","v1"));assertThatThrownBy(()->service.saveContent(20L,"logo.png","x",1L)).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("FILE_NOT_EDITABLE"));}

    @Test void deleteContentRemovesFile(){seedWorkspace(Map.of("SKILL.md","v1","note.md","n"));assertThat(service.deleteContent(20L,"note.md",1L)).extracting(SkillFileService.FileView::path).doesNotContain("note.md");assertThatThrownBy(()->service.content(20L,"note.md")).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("FILE_NOT_FOUND"));}

    @Test void deleteContentWithStaleVersionNoConflicts(){assertThatThrownBy(()->service.deleteContent(20L,"note.md",9,1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT"));}

    @Test void uploadResourceWithStaleVersionNoConflicts(){assertThatThrownBy(()->service.uploadResource(20L,"a.txt",new MockMultipartFile("f","x".getBytes()),9,1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT"));}

    @Test void deleteContentWithVersionNoHappyPath(){seedWorkspace(Map.of("SKILL.md","v1"));assertThat(service.deleteContent(20L,"SKILL.md",0,1L)).isEmpty();}

    @Test void entitySourceAccessors(){draft.storeSource("k","s",123,Map.of());assertThat(draft.getSourceSizeBytes()).isEqualTo(123);}

    @Test void deleteContentGuardsGeneratedAndMissing(){seedWorkspace(Map.of("SKILL.md","v1"));
        assertThatThrownBy(()->service.deleteContent(20L,"skill.yaml",1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("GENERATED_FILE_REQUIRED"));
        assertThatThrownBy(()->service.deleteContent(20L,"overlays/x.yaml",1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("GENERATED_FILE_REQUIRED"));
        assertThatThrownBy(()->service.deleteContent(20L,"ghost.md",1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("FILE_NOT_FOUND"));}

    @Test void uploadResourceAddsFile() throws IOException{
        seedWorkspace(Map.of("SKILL.md","v1"));
        MockMultipartFile res=new MockMultipartFile("file","a.txt","text/plain","hello".getBytes());
        assertThat(service.uploadResource(20L,"docs/a.txt",res,1L)).extracting(SkillFileService.FileView::path).contains("docs/a.txt");
        assertThat(service.content(20L,"docs/a.txt")).isEqualTo("hello");
    }

    @Test void uploadResourceVersionNoVariantAndInvalidPath(){seedWorkspace(Map.of("SKILL.md","v1"));MockMultipartFile res=new MockMultipartFile("file","a.txt","text/plain","x".getBytes());
        assertThat(service.uploadResource(20L,"a.txt",res,0,1L)).isNotEmpty();
        assertThatThrownBy(()->service.uploadResource(20L,"../evil.txt",res,1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("FILE_PATH_INVALID"));
        assertThatThrownBy(()->service.uploadResource(20L,"skill.yaml",res,1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("FILE_PATH_INVALID"));}

    @Test void resetStandardConfigRegenerates(){seedWorkspace(Map.of("SKILL.md","v1"));
        assertThat(service.resetStandardConfig(20L,1L)).extracting(SkillFileService.FileView::path).contains("skill.yaml");
        assertThat(service.resetStandardConfig(20L,0,1L)).isNotEmpty();
        assertThatThrownBy(()->service.resetStandardConfig(20L,5,1L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT"));}

    @Test void validateWarnsOnMissingFiles(){seedWorkspace(Map.of("SKILL.md","v1"));assertThat(service.validate(20L)).containsExactly("STANDARD_CONFIG_REQUIRED");seedWorkspace(Map.of());assertThat(service.validate(20L)).containsExactly("SKILL_MD_REQUIRED","STANDARD_CONFIG_REQUIRED");}

    @Test void diffAgainstBase() throws IOException{
        seedWorkspace(Map.of("SKILL.md","v2","new.md","n"));
        SkillVersionEntity base=new SkillVersionEntity(skill,null,ChangeType.INITIAL,user);ReflectionTestUtils.setField(base,"id",50L);
        base.storeSource("skills/demo/revisions/1/source.zip","a".repeat(64),10,Map.of("storageStatus","READY"));ReflectionTestUtils.setField(base,"sourceRevision",1);
        when(skills.version(50L)).thenReturn(base);
        when(storage.get("skills/demo/revisions/1/source.zip")).thenReturn(zipStream(Map.of("SKILL.md","v1".getBytes(StandardCharsets.UTF_8),"old.md","o".getBytes(StandardCharsets.UTF_8))));
        ReflectionTestUtils.setField(draft,"baseVersion",base);
        var diff=service.diff(20L);
        var byPath=new TreeMap<String,String>();diff.forEach(d->byPath.put(d.path(),d.changeType()));
        assertThat(byPath).containsEntry("SKILL.md","MODIFIED").containsEntry("new.md","ADDED").containsEntry("old.md","DELETED");
    }

    @Test void snapshotAndVersionAccessors(){seedWorkspace(Map.of("SKILL.md","v1"));assertThat(service.snapshot(20L)).containsKey("SKILL.md");assertThat(service.version(20L)).isSameAs(draft);}

    @Test void workspaceThrowsWhenSourcePending(){SkillVersionEntity fresh=new SkillVersionEntity(skill,null,ChangeType.INITIAL,user);ReflectionTestUtils.setField(fresh,"id",30L);when(skills.version(30L)).thenReturn(fresh);assertThatThrownBy(()->service.snapshot(30L)).satisfies(e->assertThat(((BusinessException)e).getCode()).isEqualTo("WORKSPACE_NOT_FOUND"));}

    private void seedWorkspace(Map<String,String> files){Map<String,byte[]> ws=new LinkedHashMap<>();files.forEach((k,v)->ws.put(k,v.getBytes(StandardCharsets.UTF_8)));@SuppressWarnings("unchecked")Map<Long,Map<String,byte[]>> all=(Map<Long,Map<String,byte[]>>)ReflectionTestUtils.getField(service,"workspaces");all.put(20L,ws);}

    private byte[] zipBytes(Map<String,byte[]> files) throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(out);for(var e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}z.finish();return out.toByteArray();}
    private InputStream zipStream(Map<String,byte[]> files) throws IOException{return new ByteArrayInputStream(zipBytes(files));}
}
