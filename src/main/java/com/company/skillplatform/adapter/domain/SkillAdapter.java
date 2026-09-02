package com.company.skillplatform.adapter.domain;

import java.util.Map;

/** SPI for platform-specific packaging. New coding agents implement this interface and register as a Spring bean. */
public interface SkillAdapter {
    String platformKey();
    String implementationKey();
    String adapterVersion();
    AdapterResult build(AdapterRequest request);
    record AdapterRequest(String skillKey,String version,Map<String,byte[]> sourceFiles,String overlayPath) {}
    record AdapterResult(byte[] artifact,String sha256,String contentType) {}
}
