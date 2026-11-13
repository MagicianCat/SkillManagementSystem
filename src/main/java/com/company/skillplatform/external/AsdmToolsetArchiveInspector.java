package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.http.HttpStatus;

/**
 * 扫描管理员导入的内层 ASDM 工具 ZIP，识别 {@code .asdm/toolsets/<toolset>/} 下一级工具集目录。
 * 只读取 ZIP 条目名称，不把内容解压到工作目录。
 */
final class AsdmToolsetArchiveInspector {
    static final int MAX_ENTRIES = 10_000;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_TOOLSET_NAME_LENGTH = 128;
    private static final String ASDM = ".asdm";
    private static final String TOOLSETS = "toolsets";

    private AsdmToolsetArchiveInspector() {}

    /** @return 工具集目录名集合；仅在目录下至少存在一个普通文件时计入。 */
    static Set<String> inspectToolsets(Path innerZip) {
        try (ZipFile zip = ZipFile.builder().setPath(innerZip).get()) {
            Set<String> seenPaths = new HashSet<>();
            Set<String> asdmRoots = new LinkedHashSet<>();
            Map<String, String> toolsetNames = new LinkedHashMap<>();
            Set<String> toolsets = new LinkedHashSet<>();
            int count = 0;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES)
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包条目过多");
                if (entry.isUnixSymlink())
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包不允许符号链接");
                // Commons Compress 会把条目名中的反斜杠规范化为斜杠，必须在原始字节上拒绝。
                byte[] rawNameBytes = entry.getRawName();
                if (rawNameBytes != null)
                    for (byte value : rawNameBytes)
                        if (value == '\\' || value == 0)
                            throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包路径不安全");
                String rawName = entry.getName();
                if (rawName == null || rawName.isBlank())
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包路径不安全");
                String name = rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName;
                if (name.isEmpty() || name.length() > MAX_PATH_LENGTH || name.indexOf('\\') >= 0
                        || name.startsWith("/") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*"))
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包路径不安全");
                String[] parts = name.split("/", -1);
                for (String part : parts)
                    if (part.isEmpty() || ".".equals(part) || "..".equals(part))
                        throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包路径不安全");
                if (!seenPaths.add(name.toLowerCase(Locale.ROOT)))
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包存在重复或大小写冲突路径");

                int asdmIndex = -1;
                for (int i = 0; i < parts.length; i++) {
                    if (ASDM.equals(parts[i])) { asdmIndex = i; break; }
                }
                if (asdmIndex < 0) continue;
                // 只允许根目录 .asdm/ 或单个打包前缀 package/.asdm/。
                if (asdmIndex > 1)
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包的 .asdm 目录层级不合法");
                String prefix = asdmIndex == 0 ? "" : parts[0];
                asdmRoots.add(prefix);
                if (asdmRoots.size() > 1)
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包存在多个 .asdm 根目录");
                if (entry.isDirectory() || parts.length < asdmIndex + 4
                        || !TOOLSETS.equals(parts[asdmIndex + 1])) continue;
                String toolset = parts[asdmIndex + 2];
                if (toolset.isEmpty() || toolset.length() > MAX_TOOLSET_NAME_LENGTH
                        || ".".equals(toolset) || "..".equals(toolset))
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包工具集目录名不合法");
                toolsets.add(toolset);
                String prior = toolsetNames.putIfAbsent(toolset.toLowerCase(Locale.ROOT), toolset);
                if (prior != null && !prior.equals(toolset))
                    throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包存在仅大小写不同的工具集目录");
            }
            if (asdmRoots.isEmpty())
                throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包缺少 .asdm 目录");
            return Set.copyOf(toolsets);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("ASDM_TOOLSET_ARCHIVE_INVALID", "ASDM 工具包损坏或无法读取");
        }
    }

    /** 声明的工具集必须全部真实存在于内层 ZIP；名称按原始大小写精确比较。 */
    static void requireToolsetsPresent(Set<String> discovered, List<AsdmCommandSupport.ToolsetCategory> declared) {
        List<String> missing = declared.stream()
                .map(AsdmCommandSupport.ToolsetCategory::toolsetName)
                .filter(name -> !discovered.contains(name))
                .distinct()
                .toList();
        if (missing.isEmpty()) return;
        String names = String.join(", ", missing);
        if (names.length() > 400) names = names.substring(0, 400) + "…";
        throw invalid("ASDM_TOOLSET_NOT_FOUND", "ASDM 包缺少工具集目录：" + names);
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}