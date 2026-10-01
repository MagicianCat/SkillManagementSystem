package com.company.skillplatform.codegraph.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CodeGraphFingerprint {
    private CodeGraphFingerprint() {}

    public record RepositoryIdentity(String logicalRepositoryKey, String treeSha, String engineType,
                                     String engineVersion, String adapterVersion, String engineConfigHash) {}
    public record BundleEntry(String alias, String repositoryFingerprint) {}

    public static String repository(RepositoryIdentity value) {
        return hash(canonical(List.of(normalizeKey(value.logicalRepositoryKey()), lower(value.treeSha()),
                upper(value.engineType()), value.engineVersion(), value.adapterVersion(), value.engineConfigHash())));
    }

    public static String bundle(List<BundleEntry> entries, String groupConfigHash) {
        var sorted = entries.stream().sorted(Comparator.comparing(BundleEntry::alias)
                .thenComparing(BundleEntry::repositoryFingerprint))
                .flatMap(entry -> List.of(entry.alias(), entry.repositoryFingerprint()).stream()).toList();
        var values = new java.util.ArrayList<>(sorted);
        values.add(groupConfigHash == null ? "" : groupConfigHash);
        return hash(canonical(values));
    }

    private static String canonical(List<String> values) {
        var builder = new StringBuilder();
        values.forEach(value -> {
            var safe = value == null ? "" : value;
            builder.append(safe.getBytes(StandardCharsets.UTF_8).length).append(':').append(safe).append('|');
        });
        return builder.toString();
    }

    private static String hash(String input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String normalizeKey(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String lower(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value.trim().toUpperCase(Locale.ROOT); }
}
