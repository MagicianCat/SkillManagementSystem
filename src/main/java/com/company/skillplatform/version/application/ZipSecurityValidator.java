package com.company.skillplatform.version.application;

import com.company.skillplatform.common.application.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ZipSecurityValidator {
    private static final Set<String> RESERVED = Set.of("skill.yaml", "overlays", "manifest.json", "install.txt", "sha256sums");
    private static final long MAX_EXPANDED = 200L * 1024 * 1024; private static final int MAX_FILES = 2000;
    public ValidatedArchive validate(InputStream input) {
        Map<String, byte[]> raw = new LinkedHashMap<>(); long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue; String name = normalize(entry.getName());
                if (name == null || name.isBlank()) throw invalid("ZIP_PATH_INVALID");
                if (name.startsWith("/") || name.contains("..") || name.contains("\\") || name.indexOf('\0') >= 0) throw invalid("ZIP_PATH_TRAVERSAL");
                String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (RESERVED.contains(name.toLowerCase(Locale.ROOT)) || RESERVED.contains(first.toLowerCase(Locale.ROOT))) throw invalid("ZIP_RESERVED_PATH");
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) throw invalid("ZIP_COMPRESSION_UNSUPPORTED");
                if (raw.containsKey(name)) throw invalid("ZIP_DUPLICATE_PATH");
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = zip.read(buffer)) >= 0) { expanded += n; if (expanded > MAX_EXPANDED) throw invalid("ZIP_EXPANDED_SIZE_LIMIT"); out.write(buffer, 0, n); }
                raw.put(name, out.toByteArray()); if (raw.size() > MAX_FILES) throw invalid("ZIP_FILE_COUNT_LIMIT");
            }
        } catch (BusinessException ex) { throw ex; } catch (Exception ex) { throw invalid("ZIP_INVALID"); }
        String root = commonRoot(raw.keySet()); Map<String, byte[]> files = new LinkedHashMap<>();
        raw.forEach((path, bytes) -> files.put(root == null ? path : path.substring(root.length() + 1), bytes));
        if (!files.containsKey("SKILL.md")) throw invalid("SKILL_MD_REQUIRED");
        return new ValidatedArchive(files, expanded, sha256(raw));
    }
    private String normalize(String path) { return path == null ? null : path.replace('\u0000', ' ').replace("//", "/"); }
    private String commonRoot(Set<String> paths) { if (paths.isEmpty()) return null; String root = null; for (String path : paths) { String first = path.contains("/") ? path.substring(0, path.indexOf('/')) : null; if (first == null) return null; root = root == null ? first : root.equals(first) ? root : null; } return root; }
    private String sha256(Map<String, byte[]> files) { try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); files.forEach((p,b)->{ digest.update(p.getBytes(StandardCharsets.UTF_8)); digest.update(b); }); return HexFormat.of().formatHex(digest.digest()); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private BusinessException invalid(String code) { return new BusinessException(code, code, HttpStatus.UNPROCESSABLE_ENTITY); }
    public record ValidatedArchive(Map<String, byte[]> files, long expandedBytes, String sha256) {}
}
