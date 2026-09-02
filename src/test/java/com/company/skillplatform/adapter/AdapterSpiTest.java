package com.company.skillplatform.adapter;
import static org.assertj.core.api.Assertions.*;
import com.company.skillplatform.adapter.application.AdapterRegistry;
import com.company.skillplatform.adapter.domain.SkillAdapter.AdapterRequest;
import com.company.skillplatform.adapter.infrastructure.*;
import java.util.*;import java.util.zip.*;import java.io.*;
import org.junit.jupiter.api.Test;
class AdapterSpiTest {
 @Test void registryDiscoversBuiltInsWithoutHardCodingConsumers(){var registry=new AdapterRegistry(List.of(new CodeBuddyAdapter(),new OpenCodeAdapter()));assertThat(registry.descriptors()).extracting(AdapterRegistry.AdapterDescriptor::platformKey).containsExactlyInAnyOrder("CODEBUDDY","OPENCODE");assertThat(registry.forPlatform("opencode").implementationKey()).isEqualTo("opencode-adapter");}
 @Test void adaptersPackageOverlayAndSourceDeterministically(){Map<String,byte[]> files=new LinkedHashMap<>();files.put("SKILL.md","body".getBytes());files.put("skill.yaml","key".getBytes());files.put("overlays/codebuddy.yaml","cb".getBytes());var result=new CodeBuddyAdapter().build(new AdapterRequest("demo","1.0.0",files,"overlays/codebuddy.yaml"));assertThat(result.artifact()).isNotEmpty();assertThat(result.sha256()).hasSize(64);try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(result.artifact()))){List<String> names=new ArrayList<>();ZipEntry e;while((e=zip.getNextEntry())!=null)names.add(e.getName());assertThat(names).contains("SKILL.md","skill.yaml","overlay.yaml").doesNotContain("overlays/codebuddy.yaml");}catch(IOException ex){throw new UncheckedIOException(ex);}}
 @Test void missingOverlayFailsClearly(){assertThatThrownBy(()->new OpenCodeAdapter().build(new AdapterRequest("demo","1.0.0",Map.of("SKILL.md",new byte[]{1}),"overlays/opencode.yaml"))).hasMessageContaining("Adapter build failed");}
}
