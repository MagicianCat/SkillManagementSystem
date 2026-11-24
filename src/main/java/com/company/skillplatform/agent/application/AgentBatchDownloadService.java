package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.repository.AgentRecommendationRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.bundle.application.BundleService;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.company.skillplatform.common.application.BusinessException;

@Service
public class AgentBatchDownloadService {
    private final AgentRunRepository runs;
    private final AgentRecommendationRepository recommendations;
    private final SkillService skills;
    private final SkillVersionRepository versions;
    private final BundleService bundles;
    private final ObjectMapper json;

    public AgentBatchDownloadService(AgentRunRepository runs, AgentRecommendationRepository recommendations,
            SkillService skills, SkillVersionRepository versions, BundleService bundles, ObjectMapper json) {
        this.runs = runs; this.recommendations = recommendations; this.skills = skills;
        this.versions = versions; this.bundles = bundles; this.json = json;
    }

    @PreAuthorize("hasAuthority('skill:download')")
    @Transactional
    public BundleService.BundleView create(String runKey, Long owner, String platform, String osType) {
        return create(runKey, owner, platform, osType, null);
    }

    @PreAuthorize("hasAuthority('skill:download')")
    @Transactional
    public BundleService.BundleView create(String runKey, Long owner, String platform, String osType, List<String> skillKeys) {
        var run = runs.findByRunKeyAndSessionOwnerUserId(runKey, owner)
                .orElseThrow(() -> new BusinessException("AGENT_RUN_NOT_FOUND", "Agent run not found", HttpStatus.NOT_FOUND));
        String requestedPlatform=AgentRequestTarget.optionalPlatform(platform),requestedOs=AgentRequestTarget.optionalOsType(osType);
        String runPlatform=AgentRequestTarget.optionalPlatform(run.getSession().getPlatform()),runOs=AgentRequestTarget.optionalOsType(run.getSession().getOsType());
        if((runPlatform!=null&&requestedPlatform!=null&&!runPlatform.equals(requestedPlatform))||(runOs!=null&&requestedOs!=null&&!runOs.equals(requestedOs)))
            throw new BusinessException("AGENT_BUNDLE_CONTEXT_MISMATCH","Bundle target must match the Agent run context",HttpStatus.UNPROCESSABLE_ENTITY);
        String targetPlatform=runPlatform!=null?runPlatform:requestedPlatform!=null?requestedPlatform:"CODEBUDDY";
        String targetOs=runOs!=null?runOs:requestedOs!=null?requestedOs:"ANY";
        var recommendation = recommendations.findByRunRef(runKey)
                .orElseThrow(() -> new BusinessException("AGENT_RECOMMENDATION_NOT_FOUND", "No recommendation found", HttpStatus.NOT_FOUND));
        Map<String, Object> payload;
        try { payload = json.readValue(recommendation.getPayload(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException("AGENT_RECOMMENDATION_INVALID", "Recommendation is invalid", HttpStatus.UNPROCESSABLE_ENTITY); }
        Object raw = payload.get("items");
        if (!(raw instanceof List<?> items) || items.isEmpty())
            throw new BusinessException("AGENT_RECOMMENDATION_EMPTY", "Recommendation has no downloadable skills", HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String,Long> recommended = new LinkedHashMap<>();
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map<?, ?> item)) continue;
            Object rawKey = item.get("skillKey");
            String key = rawKey == null ? "" : String.valueOf(rawKey);
            if (key.isBlank()) continue;
            Object rawVersionId=item.get("versionId");
            if(!(rawVersionId instanceof Number number))throw new BusinessException("AGENT_RECOMMENDATION_INVALID","Recommendation has no trusted version",HttpStatus.UNPROCESSABLE_ENTITY);
            recommended.put(key,number.longValue());
        }
        Set<String> selected=skillKeys==null||skillKeys.isEmpty()?recommended.keySet():new LinkedHashSet<>(skillKeys);
        if(!recommended.keySet().containsAll(selected))throw new BusinessException("AGENT_BUNDLE_SKILL_NOT_RECOMMENDED","Requested skill does not belong to this recommendation",HttpStatus.UNPROCESSABLE_ENTITY);
        List<com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity> roots = new ArrayList<>();
        for(String key:selected){var version=versions.findById(recommended.get(key)).orElseThrow(()->new BusinessException("AGENT_RECOMMENDATION_INVALID","Recommended version does not exist",HttpStatus.UNPROCESSABLE_ENTITY));if(!key.equals(version.getSkill().getSkillKey())||!(version.getLifecycleStatus()==LifecycleStatus.PUBLISHED||version.getLifecycleStatus()==LifecycleStatus.DEPRECATED))throw new BusinessException("AGENT_RECOMMENDATION_INVALID","Recommended version is not downloadable",HttpStatus.UNPROCESSABLE_ENTITY);roots.add(version);}
        if (roots.isEmpty()) throw new BusinessException("AGENT_RECOMMENDATION_EMPTY", "Recommendation has no downloadable skills", HttpStatus.UNPROCESSABLE_ENTITY);
        return bundles.createAgentBundle(roots,targetPlatform,targetOs,owner);
    }
    @org.springframework.beans.factory.annotation.Autowired private RetrievalPlanStore retrievalPlans;
    @org.springframework.beans.factory.annotation.Autowired private com.company.skillplatform.knowledge.application.StructuredCatalogService catalogs;

    @PreAuthorize("hasAuthority('skill:download')")
    @Transactional
    public BundleService.BundleView createCatalogBundle(String runKey,long owner,String taskId,
            String platform,String osType,Collection<Long> selection,boolean all,Long expectedTotal) {
        var run=runs.findByRunKeyAndSessionOwnerUserId(runKey,owner).orElseThrow(this::invalidCatalog);
        if(!"SUCCEEDED".equals(run.getStatus()))throw invalidCatalog();
        var task=retrievalPlans.find(runKey).stream().flatMap(plan->plan.tasks().stream())
                .filter(item->item.taskId().equals(taskId)&&"SKILL".equals(item.resourceKind())).findFirst()
                .orElseThrow(this::invalidCatalog);
        var seen=retrievalPlans.skillVersions(runKey,taskId);
        Set<Long> requested=selection==null?Set.of():new LinkedHashSet<>(selection);
        if(all&&!requested.isEmpty()||!all&&(requested.isEmpty()||!seen.keySet().containsAll(requested)))throw invalidCatalog();
        Map<Long,Long> current=new LinkedHashMap<>();
        for(int page=0;;page++){
            var data=catalogs.list(owner,run.getSession().getKnowledgeScope(),task.roleId(),"SKILL",null,page,50);
            if(all&&(expectedTotal==null||expectedTotal!=data.total()))throw invalidCatalog();
            for(var row:data.items()){
                long id=((Number)row.get("id")).longValue();
                if(all||requested.contains(id)) current.put(id,((Number)row.get("versionId")).longValue());
            }
            if((long)(page+1)*data.pageSize()>=data.total())break;
        }
        if(current.isEmpty()||(!all&&!current.keySet().equals(requested)))throw invalidCatalog();
        var roots=current.entrySet().stream().map(entry->{
            if(seen.containsKey(entry.getKey())&&!Objects.equals(seen.get(entry.getKey()),entry.getValue()))throw invalidCatalog();
            return versions.findById(entry.getValue()).filter(version->version.getSkill().getId().equals(entry.getKey())
                    &&Set.of(LifecycleStatus.PUBLISHED,LifecycleStatus.DEPRECATED).contains(version.getLifecycleStatus()))
                    .orElseThrow(this::invalidCatalog);
        }).toList();
        String targetPlatform=AgentRequestTarget.optionalPlatform(platform);
        String targetOs=AgentRequestTarget.optionalOsType(osType);
        String sessionPlatform=AgentRequestTarget.optionalPlatform(run.getSession().getPlatform());
        String sessionOs=AgentRequestTarget.optionalOsType(run.getSession().getOsType());
        if(sessionPlatform!=null&&targetPlatform!=null&&!sessionPlatform.equals(targetPlatform)
                ||sessionOs!=null&&targetOs!=null&&!sessionOs.equals(targetOs))throw invalidCatalog();
        return bundles.createAgentBundle(roots,targetPlatform!=null?targetPlatform:sessionPlatform!=null?sessionPlatform:"CODEBUDDY",
                targetOs!=null?targetOs:sessionOs!=null?sessionOs:"ANY",owner);
    }

    private BusinessException invalidCatalog() {
        return new BusinessException("CATALOG_EXPIRED","目录或版本已变化，请刷新后重新选择",HttpStatus.CONFLICT);
    }
}
