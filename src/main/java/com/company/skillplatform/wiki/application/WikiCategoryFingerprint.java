package com.company.skillplatform.wiki.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class WikiCategoryFingerprint {
    private WikiCategoryFingerprint() {}
    public static String of(String title, String markdown, Long teamId, Long parentId, List<Long> skillIds) {
        String material=title+"\u0000"+markdown+"\u0000"+teamId+"\u0000"+parentId+"\u0000"+(skillIds==null?List.of():skillIds);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
}
