package com.company.skillplatform.version.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StandardConfigService {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    public Map<String, byte[]> generated(String skillKey) {
        Map<String,Object> config = new LinkedHashMap<>(); config.put("schemaVersion", "1.0");
        Map<String,Object> skill = new LinkedHashMap<>(); skill.put("key", skillKey); skill.put("version", null); config.put("skill", skill);
        config.put("compatibility", Map.of("platforms", List.of("*"), "agents", List.of("*"), "operatingSystems", List.of("*")));
        config.put("dependencies", List.of()); config.put("capabilities", List.of()); config.put("resourceVariants", List.of());
        return Map.of("skill.yaml", write(config), "overlays/codebuddy.yaml", write(overlay("CODEBUDDY")), "overlays/opencode.yaml", write(overlay("OPENCODE")));
    }
    private Map<String,Object> overlay(String platform) { Map<String,Object> frontmatter = new LinkedHashMap<>(); frontmatter.put("keep", List.of("*")); frontmatter.put("remove", List.of()); frontmatter.put("rename", Map.of()); Map<String,Object> transform = new LinkedHashMap<>(); transform.put("frontmatter", frontmatter); transform.put("contentBlocks", List.of()); transform.put("resourceVariant", null); Map<String,Object> result = new LinkedHashMap<>(); result.put("schemaVersion", "1.0"); result.put("platform", platform); result.put("enabled", true); result.put("transform", transform); result.put("installNotes", List.of()); return result; }
    private byte[] write(Object value) { try { return yaml.writeValueAsBytes(value); } catch (Exception ex) { throw new IllegalStateException("Unable to generate standard config", ex); } }
}
