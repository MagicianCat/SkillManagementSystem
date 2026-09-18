package com.company.skillplatform.storage.domain;

import java.io.InputStream;

/** Storage boundary; domain/application code never depends on the MinIO SDK. */
public interface ObjectStoragePort {
    void put(String objectKey, InputStream content, long size, String contentType);
    InputStream get(String objectKey);
    InputStream get(String objectKey, long offset, long length);
    StorageObjectMetadata stat(String objectKey);
    boolean exists(String objectKey);
    void delete(String objectKey);

    record StorageObjectMetadata(long size, String contentType, String etag) {}
}
