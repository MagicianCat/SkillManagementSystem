package com.company.skillplatform.agent.application;

import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the platform-wide development-flow preset without invoking an LLM. */
@Service
public class AgentFastPathService {
    private static final Logger log = LoggerFactory.getLogger(AgentFastPathService.class);
    private static final String WIKI_TITLE = "研发全流程最佳实践";
    private static final int EXPECTED_SIZE = 36;
    private static final Pattern FLOW = Pattern.compile("(?:研发|开发)\\s*(全链路|全流程)|完整(?:研发|开发)流程|(?:研发|开发)全流程最佳实践", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("(推荐|安装|获取|一键|全部|所有|哪些|有什么|列出|查看).{0,12}(skill|技能)|(?:skill|技能).{0,12}(推荐|安装|获取|全部|所有|哪些|有什么|列出|查看)", Pattern.CASE_INSENSITIVE);
    private final WikiDocumentRepository documents;
    private final WikiDocumentSkillRepository links;
    private final ObjectMapper json;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public AgentFastPathService(WikiDocumentRepository documents, WikiDocumentSkillRepository links,
            ObjectMapper json) {
        this.documents = documents; this.links = links; this.json = json;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warm() { refreshInternal(); }

    @Scheduled(fixedDelayString = "${agent.fast-path.refresh-ms:60000}")
    @Transactional(readOnly = true)
    public void refresh() {
        refreshInternal();
    }

    private void refreshInternal() {
        try {
            List<WikiDocumentEntity> found = documents.findByTitleAndPlatformVisibleTrueAndStatus(WIKI_TITLE, "ACTIVE");
            if (found.size() != 1) { snapshot.set(null); log.warn("event=agent.fast_path.cache_invalid reason=wiki_count count={}", found.size()); return; }
            WikiDocumentEntity document = found.get(0);
            var linked = links.findByDocumentIdOrderBySortOrderAsc(document.getId());
            if (linked.size() != EXPECTED_SIZE) { snapshot.set(null); log.warn("event=agent.fast_path.cache_invalid reason=skill_count count={}", linked.size()); return; }
            List<CachedSkill> items = new ArrayList<>();
            for (var link : linked) {
                SkillEntity skill = link.getSkill();
                var version = skill.getLatestPublishedVersion();
                if (skill.getStatus() != SkillStatus.ACTIVE || version == null
                        || !(version.getLifecycleStatus() == LifecycleStatus.PUBLISHED || version.getLifecycleStatus() == LifecycleStatus.DEPRECATED)) {
                    snapshot.set(null); log.warn("event=agent.fast_path.cache_invalid reason=skill_unavailable skillKey={}", skill.getSkillKey());
                    return;
                }
                items.add(new CachedSkill(skill.getSkillKey(), skill.getDisplayName(), skill.getDescription(),
                        skill.getDevelopmentStage() == null ? null : skill.getDevelopmentStage().name(),
                        version.getId(), version.getVersion()));
            }
            snapshot.set(new Snapshot(document.getId(), document.getVersionNo(), List.copyOf(items)));
            log.info("event=agent.fast_path.cache_refreshed wikiId={} itemCount={}", document.getId(), items.size());
        } catch (RuntimeException ex) {
            log.warn("event=agent.fast_path.cache_refresh_failed", ex);
        }
    }

    @Transactional(readOnly = true)
    public Optional<FastPath> resolve(String content, String platform, String osType) {
        if (!matches(content)) return Optional.empty();
        Snapshot current = snapshot.get();
        if (current == null) return Optional.empty();
        String targetPlatform = blankToNull(platform);
        String targetOs = blankToNull(osType);
        List<Map<String, Object>> items = current.skills().stream().map(skill -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillKey", skill.skillKey()); item.put("displayName", skill.displayName()); item.put("description", skill.description());
            item.put("version", skill.version()); item.put("versionId", skill.versionId()); item.put("developmentStage", skill.stage());
            item.put("priority", "RECOMMENDED"); item.put("reason", "平台《研发全流程最佳实践》推荐");
            if (targetPlatform != null) item.put("platform", targetPlatform);
            if (targetOs != null) item.put("osType", targetOs);
            item.put("detailPath", "/skills/" + skill.skillKey());
            return item;
        }).toList();
        String summary = "平台《研发全流程最佳实践》共推荐 " + items.size() + " 个 Skill，以下为当前平台维护的完整清单。";
        try {
            String payload = json.writeValueAsString(Map.of("accepted", true, "runRef", "", "summary", summary, "status", "VALID", "items", items));
            return Optional.of(new FastPath(summary, items, payload));
        } catch (JsonProcessingException ex) {
            log.warn("event=agent.fast_path.payload_failed", ex);
            return Optional.empty();
        }
    }

    public boolean matches(String content) { return content != null && FLOW.matcher(content).find() && ACTION.matcher(content).find(); }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    public record FastPath(String summary, List<Map<String, Object>> items, String payload) {}
    private record Snapshot(Long wikiId, int wikiVersion, List<CachedSkill> skills) {}
    private record CachedSkill(String skillKey, String displayName, String description, String stage, Long versionId, String version) {}
}
