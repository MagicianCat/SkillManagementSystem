package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.http.HttpStatus;

/** Inspects only the outer container; the inner tool ZIP is never unpacked. */
final class AsdmOuterArchive {
    static final long MAX_MARKDOWN_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 32;
    record Entries(String markdownPath, String packagePath, String packageFilename) {}

    static Entries inspect(Path path, long maxInnerBytes) {
        try (ZipFile zip = ZipFile.builder().setPath(path).get()) {
            String markdown = null, tool = null;
            boolean extraFile = false;
            Set<String> seen = new HashSet<>();
            int count = 0;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 条目过多");
                String name = entry.getName();
                if (name == null || name.isBlank() || name.length() > 512 || name.indexOf('\\') >= 0 || name.startsWith("/") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*"))
                    throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 路径不安全");
                String[] parts = name.split("/", -1);
                if (parts.length > 3 || (entry.isDirectory() && parts.length > 2)) throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 路径过深");
                for (String part : parts) if (part.equals(".") || part.equals("..")) throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 路径不安全");
                if (!seen.add(name.toLowerCase(Locale.ROOT))) throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 路径重复");
                if (entry.isUnixSymlink()) throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 不允许符号链接");
                if (entry.isDirectory()) continue;
                if (entry.getSize() > 0 && entry.getCompressedSize() > 0 && entry.getSize() > 1024 * 1024 && entry.getSize() / entry.getCompressedSize() > 200)
                    throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 压缩比异常");
                if (entry.getSize() == 0) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 含空文件");
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".md")) {
                    if (markdown != null) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 只能包含一篇 Markdown");
                    markdown = name;
                    readLimited(zip.getInputStream(entry), MAX_MARKDOWN_BYTES, "ASDM_ARCHIVE_INVALID");
                } else if (lower.endsWith(".zip")) {
                    if (tool != null) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 只能包含一个工具 ZIP");
                    tool = name;
                    countLimited(zip.getInputStream(entry), maxInnerBytes, "ASDM_ARCHIVE_INVALID");
                } else extraFile = true;
            }
            if (markdown == null && tool == null) throw invalid("ASDM_BOTH_MISSING", "ASDM 工具包同时缺少 MD 说明文件和工具 ZIP");
            if (markdown == null) throw invalid("ASDM_MD_MISSING", "ASDM 工具包缺少 Markdown 使用说明文件");
            if (tool == null) throw invalid("ASDM_PACKAGE_MISSING", "ASDM 工具包缺少工具 ZIP 压缩包");
            if (extraFile) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 含额外文件");
            if (!parent(markdown).equals(parent(tool)) || parent(markdown).split("/").length > 1)
                throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 的两个文件必须处于根目录或同一个一级目录");
            return new Entries(markdown, tool, tool.substring(tool.lastIndexOf('/') + 1));
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 外层 ZIP 损坏或无法读取"); }
    }

    static byte[] readMarkdown(Path path, String entry) {
        try (ZipFile zip = ZipFile.builder().setPath(path).get()) { return readLimited(zip.getInputStream(zip.getEntry(entry)), MAX_MARKDOWN_BYTES, "ASDM_MD_INVALID"); }
        catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("ASDM_ARCHIVE_INVALID", "ASDM Markdown 无法读取"); }
    }

    static Path extractTool(Path path, String entry, long limit) {
        try {
            Path temp = Files.createTempFile("asdm-inner-", ".zip");
            try (ZipFile zip = ZipFile.builder().setPath(path).get(); InputStream in = zip.getInputStream(zip.getEntry(entry)); var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192]; long count = 0; int n;
                while ((n = in.read(buffer)) != -1) { count += n; if (count > limit) throw invalid("ASDM_INNER_ZIP_TOO_LARGE", "ASDM 工具包超过大小限制"); out.write(buffer, 0, n); }
                if (count == 0) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 工具包为空");
                return temp;
            } catch (Exception ex) { Files.deleteIfExists(temp); throw ex; }
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("ASDM_ARCHIVE_INVALID", "ASDM 工具包无法读取"); }
    }

    private static String parent(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i); }
    private static byte[] readLimited(InputStream stream, long limit, String code) throws IOException {
        try (InputStream in = stream; var out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; long count = 0; int n;
            while ((n = in.read(buffer)) != -1) { count += n; if (count > limit) throw invalid(code, "ASDM 外层 ZIP 条目超过大小限制"); out.write(buffer, 0, n); }
            if (count == 0) throw invalid("ASDM_STRUCTURE_INVALID", "ASDM 外层 ZIP 含空文件");
            return out.toByteArray();
        }
    }
    private static void countLimited(InputStream stream, long limit, String code) throws IOException {
        try (InputStream in=stream) {
            byte[] buffer=new byte[8192]; long count=0; int n;
            while ((n=in.read(buffer))!=-1) { count+=n; if (count>limit) throw invalid(code,"ASDM 工具 ZIP 超过大小限制"); }
            if (count==0) throw invalid("ASDM_STRUCTURE_INVALID","ASDM 外层 ZIP 含空文件");
        }
    }
    private static BusinessException invalid(String code, String message) { return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY); }
}
