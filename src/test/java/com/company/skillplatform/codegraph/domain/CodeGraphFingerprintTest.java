package com.company.skillplatform.codegraph.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphFingerprintTest {
    @Test
    void repositoryFingerprintIsCanonicalAndSensitiveToCompatibilityInputs() {
        var a = new CodeGraphFingerprint.RepositoryIdentity("GitHub.COM/Org/Repo", "ABCDEF", "GITNEXUS", "1.0", "adapter-1", "cfg");
        var normalized = new CodeGraphFingerprint.RepositoryIdentity("github.com/org/repo", "abcdef", "GITNEXUS", "1.0", "adapter-1", "cfg");
        assertThat(CodeGraphFingerprint.repository(a)).isEqualTo(CodeGraphFingerprint.repository(normalized));

        assertThat(CodeGraphFingerprint.repository(new CodeGraphFingerprint.RepositoryIdentity(
                "github.com/org/repo", "abcdef", "GITNEXUS", "1.0", "adapter-2", "cfg")))
                .isNotEqualTo(CodeGraphFingerprint.repository(normalized));
    }

    @Test
    void bundleHashDoesNotDependOnInputOrderButDoesDependOnAlias() {
        var first = List.of(new CodeGraphFingerprint.BundleEntry("backend", "aaa"),
                new CodeGraphFingerprint.BundleEntry("frontend", "bbb"));
        var reversed = List.of(first.get(1), first.get(0));
        assertThat(CodeGraphFingerprint.bundle(first, "group-cfg"))
                .isEqualTo(CodeGraphFingerprint.bundle(reversed, "group-cfg"));
        assertThat(CodeGraphFingerprint.bundle(List.of(
                new CodeGraphFingerprint.BundleEntry("api", "aaa"), first.get(1)), "group-cfg"))
                .isNotEqualTo(CodeGraphFingerprint.bundle(first, "group-cfg"));
    }
}
