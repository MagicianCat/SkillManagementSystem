package com.company.skillplatform.storage.domain;

import java.io.InputStream;

/** Storage boundary; domain/application code never depends on the MinIO SDK. */
public interface ObjectStoragePort {
    void put(String objectKey, InputStream content, long size, String contentType);
    InputStream get(String objectKey);
    boolean exists(String objectKey);
}
