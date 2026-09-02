package com.company.skillplatform.version.application;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class StandardConfigServiceTest { @Test void generatesDefaultConfigAndOverlays(){var files=new StandardConfigService().generated("demo-skill");assertThat(files).containsKeys("skill.yaml","overlays/codebuddy.yaml","overlays/opencode.yaml");assertThat(new String(files.get("skill.yaml"))).contains("demo-skill","schemaVersion");assertThat(new String(files.get("overlays/opencode.yaml"))).contains("OPENCODE");} }
