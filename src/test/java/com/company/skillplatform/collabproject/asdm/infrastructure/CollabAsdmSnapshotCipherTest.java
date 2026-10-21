package com.company.skillplatform.collabproject.asdm.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollabAsdmSnapshotCipherTest {
    private CollabAsdmTokenCipher cipher() {
        var props=new CollabAsdmProperties(true,List.of("gitlab.example"),Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofSeconds(60),Duration.ofSeconds(300),Duration.ofSeconds(5),Duration.ofSeconds(20),1_048_576,5000,20,
                true,true,20000,300,20L*1024*1024,200L*1024*1024,false,4,8,Duration.ofHours(24));
        var cipher=new CollabAsdmTokenCipher(props); cipher.initialize(); return cipher;
    }

    @Test void encryptsAndRestoresArbitraryBinarySnapshot() {
        byte[] original=new byte[]{0,1,-1,13,10,42,0,99};
        byte[] sealed=cipher().encryptBytes(original);
        assertFalse(java.util.Arrays.equals(original,sealed));
        assertArrayEquals(original,cipher().decryptBytes(sealed));
    }

    @Test void rejectsTamperedSnapshot() {
        byte[] sealed=cipher().encryptBytes(new byte[]{1,2,3}); sealed[sealed.length-1]^=1;
        assertThrows(IllegalStateException.class,()->cipher().decryptBytes(sealed));
    }
}
