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
}
