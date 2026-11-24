package com.company.skillplatform.knowledge.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExternalRoleMetadata(String sourceSystem, String sourceResourceId, String resourceType, List<Role> roles) {
    public static final ExternalRoleMetadata LOCAL = new ExternalRoleMetadata(null, null, null, List.of());
    private static final ObjectMapper JSON = new ObjectMapper();

    public ExternalRoleMetadata {
        roles = List.copyOf(roles);
    }

    public record Role(long id, String name, String externalRoleId) {}

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", sourceSystem == null ? "LOCAL_WIKI" : "EXTERNAL_RESOURCE");
        payload.put("sourceSystem", sourceSystem);
        payload.put("sourceResourceId", sourceResourceId);
        payload.put("externalResourceType", resourceType);
        payload.put("externalRoleIds", roles.stream().map(Role::id).toList());
        payload.put("externalRoleNames", roles.stream().map(Role::name).toList());
        payload.put("externalRoles", roles);
        payload.put("roleMetadataSchemaVersion", 1);
        try {
            payload.put("roleMetadataFingerprint", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(payload))));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint Wiki role metadata", e);
        }
        return payload;
    }
}
