package com.company.skillplatform.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.common.application.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AsdmToolsetArchiveInspectorTest {
    private final List<Path> tempFiles = new ArrayList<>();

    @AfterEach void cleanup() {
        for (Path path : tempFiles) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }

    private Path zip(String... entries) throws IOException {
        Path path = Files.createTempFile("asdm-toolset-inspector-", ".zip");
        tempFiles.add(path);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) out.write("x".getBytes());
                out.closeEntry();
            }
        }
        return path;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private static AsdmCommandSupport.ToolsetCategory declared(String name) {
        return new AsdmCommandSupport.ToolsetCategory(name, "other", "OTHER");
    }

    @Test void findsToolsetsAtAsdmRootIncludingChineseNames() throws Exception {
        Path archive = zip(
                ".asdm/toolsets/code-dev-sc274/actions/code-review.md",
                ".asdm/toolsets/中文工具集/README.md",
                ".asdm/toolsets/README.md");
        assertThat(AsdmToolsetArchiveInspector.inspectToolsets(archive))
                .containsExactlyInAnyOrder("code-dev-sc274", "中文工具集");
    }

    @Test void supportsSinglePackagePrefix() throws Exception {
        Path archive = zip(
                "package/.asdm/toolsets/api-test-gen/actions/api-test-gen.md",
                "package/extra.txt");
        assertThat(AsdmToolsetArchiveInspector.inspectToolsets(archive)).containsExactly("api-test-gen");
    }

    @Test void derivesToolsetFromFullFilePathWithoutDirectoryEntry() throws Exception {
        Path archive = zip(".asdm/toolsets/req-analyzer-ylj/actions/req-clarify.md");
        assertThat(AsdmToolsetArchiveInspector.inspectToolsets(archive)).containsExactly("req-analyzer-ylj");
    }

    @Test void emptyToolsetDirectoryIsNotDiscovered() throws Exception {
        Path archive = zip(".asdm/toolsets/empty-toolset/", ".asdm/toolsets/real-toolset/README.md");
        assertThat(AsdmToolsetArchiveInspector.inspectToolsets(archive)).containsExactly("real-toolset");
    }

    @Test void actionFileIsNotMistakenForToolset() throws Exception {
        Path archive = zip(".asdm/toolsets/code-dev-sc274/actions/code-review.md");
        AsdmToolsetArchiveInspector.requireToolsetsPresent(
                AsdmToolsetArchiveInspector.inspectToolsets(archive), List.of(declared("code-dev-sc274")));
        assertCode(() -> AsdmToolsetArchiveInspector.requireToolsetsPresent(
                AsdmToolsetArchiveInspector.inspectToolsets(archive), List.of(declared("code-review"))),
                "ASDM_TOOLSET_NOT_FOUND");
    }

    @Test void declaredToolsetMustMatchOriginalCaseExactly() throws Exception {
        Path archive = zip(".asdm/toolsets/code-dev/actions/a.md");
        assertCode(() -> AsdmToolsetArchiveInspector.requireToolsetsPresent(
                AsdmToolsetArchiveInspector.inspectToolsets(archive), List.of(declared("Code-Dev"))),
                "ASDM_TOOLSET_NOT_FOUND");
    }

    @Test void rejectsMultipleAsdmRoots() throws Exception {
        Path archive = zip(".asdm/toolsets/a/x.md", "package/.asdm/toolsets/b/x.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(archive), "ASDM_TOOLSET_ARCHIVE_INVALID");
    }

    @Test void rejectsDeepAsdmPrefix() throws Exception {
        Path archive = zip("a/b/.asdm/toolsets/t/x.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(archive), "ASDM_TOOLSET_ARCHIVE_INVALID");
    }

    @Test void rejectsCaseConflictingToolsetDirectories() throws Exception {
        Path archive = zip(".asdm/toolsets/code-dev/x.md", ".asdm/toolsets/Code-Dev/y.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(archive), "ASDM_TOOLSET_ARCHIVE_INVALID");
    }

    @Test void rejectsUnsafePathsAndSymlinks() throws Exception {
        Path traversal = zip(".asdm/toolsets/../evil/x.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(traversal), "ASDM_TOOLSET_ARCHIVE_INVALID");
        Path backslash = zip(".asdm\\toolsets\\t\\x.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(backslash), "ASDM_TOOLSET_ARCHIVE_INVALID");
    }

    @Test void rejectsArchiveWithoutAsdmRoot() throws Exception {
        Path archive = zip("toolsets/code-dev/actions/x.md");
        assertCode(() -> AsdmToolsetArchiveInspector.inspectToolsets(archive), "ASDM_TOOLSET_ARCHIVE_INVALID");
    }

    @Test void missingToolsetMessageListsAllMissingNames() throws Exception {
        Path archive = zip(".asdm/toolsets/present/x.md");
        assertThatThrownBy(() -> AsdmToolsetArchiveInspector.requireToolsetsPresent(
                AsdmToolsetArchiveInspector.inspectToolsets(archive),
                List.of(declared("present"), declared("missing-one"), declared("missing-two"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ASDM_TOOLSET_NOT_FOUND");
                    assertThat(ex.getMessage()).contains("missing-one", "missing-two").doesNotContain("present");
                });
    }
}