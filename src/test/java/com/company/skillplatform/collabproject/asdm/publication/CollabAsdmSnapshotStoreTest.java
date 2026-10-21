package com.company.skillplatform.collabproject.asdm.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmSnapshotStore;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollabAsdmSnapshotStoreTest {
    private CollabAsdmProperties properties(long maxBytes) {
        return new CollabAsdmProperties(true,List.of("gitlab.example"),Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofSeconds(60),Duration.ofSeconds(300),Duration.ofSeconds(5),Duration.ofSeconds(20),1_048_576,5000,20,
                true,true,20000,300,maxBytes,200L*1024*1024,false,4,8,Duration.ofHours(24));
    }
    private CollabAsdmTokenCipher cipher(CollabAsdmProperties p) { var c=new CollabAsdmTokenCipher(p); c.initialize(); return c; }

    @Test void encryptsStoredBytesAndVerifiesHashOnRead() throws Exception {
        var props=properties(1024); var cipher=cipher(props); var storage=mock(ObjectStoragePort.class);
        var store=new CollabAsdmSnapshotStore(storage,cipher,props);
        byte[] original={0,1,2,-1,10};
        store.store(55,"workspace/REQ-1/spec.bin",original,"application/octet-stream");
        var storedRef=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        doAnswer(invocation->{storedRef.set(((java.io.InputStream)invocation.getArgument(1)).readAllBytes());return null;})
                .when(storage).put(anyString(),any(java.io.InputStream.class),anyLong(),anyString());
        // store() was called before the answer above, so replay with a fresh key to capture the persisted ciphertext.
        var snapshot=store.store(55,"workspace/REQ-1/spec.bin",original,"application/octet-stream");
        verify(storage).put(eq(snapshot.storageKey()),any(),anyLong(),eq("application/octet-stream"));
        byte[] stored=storedRef.get();
        assertFalse(java.util.Arrays.equals(original,stored));
        when(storage.get(snapshot.storageKey())).thenReturn(new ByteArrayInputStream(stored));
        assertArrayEquals(original,store.read(snapshot.storageKey(),original.length,snapshot.sha256()));
        var storageKey=snapshot.storageKey(); var expectedSha=snapshot.sha256();
        assertThrows(IllegalStateException.class,()->store.read(storageKey,original.length,"0".repeat(64)));
    }

    @Test void enforcesPerFileLimitBeforeWriting() {
        var props=properties(2); var storage=mock(ObjectStoragePort.class); var store=new CollabAsdmSnapshotStore(storage,cipher(props),props);
        assertThrows(IllegalArgumentException.class,()->store.store(1,"x.bin",new byte[3],"application/octet-stream"));
        verifyNoInteractions(storage);
    }
}
