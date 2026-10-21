package com.company.skillplatform.collabproject.asdm.publication;

import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmTokenCipher;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Encrypted, content-addressed staging for immutable GitLab bytes before Feishu publication. */
@Component
public class CollabAsdmSnapshotStore {
    private final ObjectStoragePort storage;
    private final CollabAsdmTokenCipher cipher;
    private final CollabAsdmProperties properties;

    public CollabAsdmSnapshotStore(ObjectStoragePort storage, CollabAsdmTokenCipher cipher, CollabAsdmProperties properties) {
        this.storage=storage; this.cipher=cipher; this.properties=properties;
    }

    public Snapshot store(long packageId, String sourcePath, byte[] bytes, String mediaType) {
        if (bytes == null) throw new IllegalArgumentException("Snapshot bytes are required");
        if (bytes.length > properties.maxFileBytes()) throw new IllegalArgumentException("ASDM_FILE_OVERSIZE");
        String sha256 = sha256(bytes);
        String key = "collab/asdm/packages/" + packageId + "/" + sha256 + "/" + UUID.randomUUID();
        byte[] encrypted = cipher.encryptBytes(bytes);
        storage.put(key,new ByteArrayInputStream(encrypted),encrypted.length,"application/octet-stream");
        java.util.Arrays.fill(encrypted,(byte)0);
        return new Snapshot(key,sha256,bytes.length,sourcePath,mediaType);
    }

    public byte[] read(String key,long expectedSize,String expectedSha256) {
        if (key == null || !key.startsWith("collab/asdm/packages/")) throw new IllegalArgumentException("Invalid ASDM snapshot key");
        if (expectedSize < 0 || expectedSize > properties.maxFileBytes()) throw new IllegalArgumentException("ASDM_FILE_OVERSIZE");
        try (var in=storage.get(key)) {
            byte[] encrypted=in.readNBytes(Math.toIntExact(expectedSize + 29));
            if (encrypted.length < 29 || in.read()!=-1) throw new IllegalStateException("ASDM_SNAPSHOT_LENGTH_MISMATCH");
            byte[] bytes=cipher.decryptBytes(encrypted);
            java.util.Arrays.fill(encrypted,(byte)0);
            if (bytes.length != expectedSize || !sha256(bytes).equalsIgnoreCase(expectedSha256)) {
                java.util.Arrays.fill(bytes,(byte)0);
                throw new IllegalStateException("ASDM_SNAPSHOT_CHECKSUM_MISMATCH");
            }
            return bytes;
        } catch (IOException ex) { throw new IllegalStateException("ASDM_SNAPSHOT_READ_FAILED",ex); }
    }

    public void delete(String key) {
        if (key != null && key.startsWith("collab/asdm/packages/")) storage.delete(key);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public record Snapshot(String storageKey,String sha256,long sizeBytes,String sourcePath,String mediaType) {}
}
