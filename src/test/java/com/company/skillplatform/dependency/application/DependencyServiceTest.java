package com.company.skillplatform.dependency.application;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import com.company.skillplatform.dependency.domain.DependencyType;import com.company.skillplatform.dependency.infrastructure.entity.SkillVersionDependencyEntity;import com.company.skillplatform.dependency.infrastructure.repository.SkillVersionDependencyRepository;import com.company.skillplatform.skill.application.SkillService;import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;import com.company.skillplatform.user.domain.IdentityProviderType;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.version.domain.*;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;import java.time.Instant;import java.util.*;import org.junit.jupiter.api.*;import org.springframework.test.util.ReflectionTestUtils;
class DependencyServiceTest{SkillVersionDependencyRepository deps=mock(SkillVersionDependencyRepository.class);SkillVersionRepository versions=mock(SkillVersionRepository.class);SkillRepository skills=mock(SkillRepository.class);SkillService skillService=mock(SkillService.class);DependencyService service;SkillEntity root,target;SkillVersionEntity draft,published;
 @BeforeEach void setup(){service=new DependencyService(deps,versions,skills,skillService,new SemanticVersionPolicy());IamUserEntity u=new IamUserEntity(IdentityProviderType.MOCK,"u","u","h","U",null);root=new SkillEntity("root","Root","d",null,u);target=new SkillEntity("target","Target","d",null,u);ReflectionTestUtils.setField(root,"id",1L);ReflectionTestUtils.setField(target,"id",2L);draft=new SkillVersionEntity(root,null,ChangeType.INITIAL,u);published=new SkillVersionEntity(target,null,ChangeType.INITIAL,u);ReflectionTestUtils.setField(draft,"id",10L);ReflectionTestUtils.setField(published,"id",20L);published.submit("1.0.0",u);published.approve(u);published.publish(Instant.EPOCH,u);when(skillService.version(10L)).thenReturn(draft);when(skillService.version(20L)).thenReturn(published);when(skills.findBySkillKey("target")).thenReturn(Optional.of(target));when(versions.incrementVersion(10L,0)).thenReturn(1);}
 @Test void replacesListsResolvesAndFindsDependents(){var command=new DependencyService.DependencyCommand("target",">=1.0.0 <2.0.0",DependencyType.RUNTIME,true);assertThat(service.replace(10L,0,List.of(command),1L)).hasSize(1);SkillVersionDependencyEntity entity=new SkillVersionDependencyEntity(draft,target,">=1.0.0 <2.0.0",DependencyType.RUNTIME,true,0);when(deps.findByVersionIdOrderBySortOrder(10L)).thenReturn(List.of(entity));when(deps.findByVersionIdOrderBySortOrder(20L)).thenReturn(List.of());when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(2L),anyCollection())).thenReturn(Optional.of(published));when(deps.findByDependencySkillId(2L)).thenReturn(List.of(entity));assertThat(service.list(10L)).hasSize(1);assertThat(service.resolve(10L).items()).extracting("version").containsExactly("1.0.0");assertThat(service.dependents(20L)).hasSize(1);}
 @Test void rejectsSelfAndStaleUpdate(){when(skills.findBySkillKey("root")).thenReturn(Optional.of(root));assertThatThrownBy(()->service.replace(10L,0,List.of(new DependencyService.DependencyCommand("root","*",DependencyType.RUNTIME,true)),1L)).isInstanceOf(RuntimeException.class);when(versions.incrementVersion(10L,2)).thenReturn(0);assertThatThrownBy(()->service.replace(10L,2,List.of(new DependencyService.DependencyCommand("target","*",DependencyType.RUNTIME,true)),1L)).isInstanceOf(RuntimeException.class);}
 @Test void resolvesTransitiveDependenciesAndReportsConstraintConflict(){
  IamUserEntity leafUser=new IamUserEntity(IdentityProviderType.MOCK,"l","l","h","L",null); SkillEntity leaf=new SkillEntity("leaf","Leaf","d",null,leafUser);ReflectionTestUtils.setField(leaf,"id",3L);
  SkillVersionEntity leafVersion=new SkillVersionEntity(leaf,null,ChangeType.INITIAL,leafUser);ReflectionTestUtils.setField(leafVersion,"id",30L);leafVersion.submit("1.0.0",leafUser);leafVersion.approve(leafUser);leafVersion.publish(Instant.EPOCH,leafUser);
  SkillVersionDependencyEntity transitiveFirst=new SkillVersionDependencyEntity(published,leaf,"*",DependencyType.RUNTIME,true,0);
  SkillVersionDependencyEntity transitiveSecond=new SkillVersionDependencyEntity(published,leaf,"*",DependencyType.RUNTIME,true,1);
  SkillVersionDependencyEntity first=new SkillVersionDependencyEntity(draft,target,"*",DependencyType.RUNTIME,true,0);
  when(deps.findByVersionIdOrderBySortOrder(10L)).thenReturn(List.of(first));
  when(deps.findByVersionIdOrderBySortOrder(20L)).thenReturn(List.of(transitiveFirst,transitiveSecond));
  when(deps.findByVersionIdOrderBySortOrder(30L)).thenReturn(List.of());
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(2L),anyCollection())).thenReturn(Optional.of(published));
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(3L),anyCollection())).thenReturn(Optional.of(leafVersion));
  var result=service.resolve(10L);
  assertThat(result.items()).extracting("skillKey").contains("target","leaf");
 }
 @Test void reportsVersionConstraintConflict(){
  SkillVersionDependencyEntity first=new SkillVersionDependencyEntity(draft,target,">=2.0.0",DependencyType.RUNTIME,true,0);
  SkillVersionDependencyEntity second=new SkillVersionDependencyEntity(draft,target,"<2.0.0",DependencyType.RUNTIME,true,1);
  when(deps.findByVersionIdOrderBySortOrder(10L)).thenReturn(List.of(first,second));
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(2L),anyCollection())).thenReturn(Optional.of(published));
  assertThat(service.resolve(10L).conflicts()).anyMatch(c->c.startsWith("VERSION_CONFLICT"));
 }
 @Test void validatesAllSupportedConstraintOperators(){
  when(deps.findByVersionIdOrderBySortOrder(10L)).thenReturn(List.of(new SkillVersionDependencyEntity(draft,target,">0.0.0 <=1.0.0 1.0.0",DependencyType.RUNTIME,true,0)));
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(2L),anyCollection())).thenReturn(Optional.of(published));
  assertThat(service.resolve(10L).conflicts()).isEmpty();
  assertThatThrownBy(()->service.replace(10L,0,List.of(new DependencyService.DependencyCommand("target","not-semver",DependencyType.RUNTIME,true)),1L)).hasMessageContaining("Invalid SemVer constraint");
 }
 @Test void reportsCycleWithPath(){
  SkillVersionDependencyEntity back=new SkillVersionDependencyEntity(published,root,"*",DependencyType.RUNTIME,true,0);
  SkillVersionDependencyEntity forward=new SkillVersionDependencyEntity(draft,target,"*",DependencyType.RUNTIME,true,0);
  when(deps.findByVersionIdOrderBySortOrder(10L)).thenReturn(List.of(forward));
  when(deps.findByVersionIdOrderBySortOrder(20L)).thenReturn(List.of(back));
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(2L),anyCollection())).thenReturn(Optional.of(published));
  when(versions.findFirstBySkillIdAndLifecycleStatusInOrderByPublishedAtDesc(eq(1L),anyCollection())).thenReturn(Optional.of(draft));
  assertThat(service.resolve(10L).conflicts()).anyMatch(c->c.startsWith("DEPENDENCY_CYCLE"));
 }
}
