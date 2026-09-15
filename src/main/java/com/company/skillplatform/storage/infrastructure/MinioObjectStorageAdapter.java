package com.company.skillplatform.storage.infrastructure;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import io.minio.*;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorageAdapter implements ObjectStoragePort {
    private static final Logger log=LoggerFactory.getLogger(MinioObjectStorageAdapter.class); private final MinioClient client; private final MinioStorageProperties properties;
    public MinioObjectStorageAdapter(MinioClient client, MinioStorageProperties properties) { this.client = client; this.properties = properties; }
    @Override public void put(String key, InputStream content, long size, String contentType) {
        try { if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build()); client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(key).stream(content, size, -1).contentType(contentType).build()); log.info("event=storage.put.completed bucket={} key={} sizeBytes={}",properties.bucket(),key,size); }
        catch (Exception ex) { log.error("event=storage.put.failed bucket={} key={} errorCode=STORAGE_WRITE_FAILED",properties.bucket(),key,ex); throw new BusinessException("STORAGE_WRITE_FAILED", "Unable to store source revision", HttpStatus.BAD_GATEWAY); }
    }
    @Override public InputStream get(String key) { try { return client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(key).build()); } catch (Exception ex) { log.error("event=storage.get.failed bucket={} key={} errorCode=STORAGE_READ_FAILED",properties.bucket(),key,ex); throw new BusinessException("STORAGE_READ_FAILED", "Unable to read source revision", HttpStatus.BAD_GATEWAY); } }
    @Override public boolean exists(String key) { try { client.statObject(StatObjectArgs.builder().bucket(properties.bucket()).object(key).build()); return true; } catch (Exception ex) { return false; } }
    @Override public void delete(String key) { try { client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(key).build()); } catch (Exception ex) { log.warn("event=storage.delete.failed bucket={} key={}", properties.bucket(), key, ex); } }
}
