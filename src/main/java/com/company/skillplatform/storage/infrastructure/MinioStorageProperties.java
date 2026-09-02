package com.company.skillplatform.storage.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skill-platform.storage.minio")
public record MinioStorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {}
