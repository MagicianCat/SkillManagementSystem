package com.company.skillplatform.version.application;

import static org.assertj.core.api.Assertions.*;
import java.io.*;import java.util.zip.*;
import org.junit.jupiter.api.Test;

class ZipSecurityValidatorTest {
    private final ZipSecurityValidator validator=new ZipSecurityValidator();
    @Test void acceptsSingleRootAndReportsFiles(){var archive=validator.validate(zip("bundle/SKILL.md","hello","bundle/assets/a.txt","a"));assertThat(archive.files()).containsKey("SKILL.md").containsKey("assets/a.txt");assertThat(archive.expandedBytes()).isEqualTo(6);assertThat(archive.sha256()).hasSize(64);}
    @Test void acceptsFlatArchive(){assertThat(validator.validate(zip("SKILL.md","ok")).files()).containsKey("SKILL.md");}
    @Test void rejectsUnsafeAndReservedEntries(){assertThatThrownBy(()->validator.validate(zip("../SKILL.md","x"))).hasMessageContaining("ZIP_PATH_TRAVERSAL");assertThatThrownBy(()->validator.validate(zip("skill.yaml","x","SKILL.md","x"))).hasMessageContaining("ZIP_RESERVED_PATH");assertThatThrownBy(()->validator.validate(zip("other.txt","x"))).hasMessageContaining("SKILL_MD_REQUIRED");}
    private InputStream zip(String... pairs){try{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(int i=0;i<pairs.length;i+=2){z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes());z.closeEntry();}}return new ByteArrayInputStream(out.toByteArray());}catch(IOException e){throw new UncheckedIOException(e);}}
}
