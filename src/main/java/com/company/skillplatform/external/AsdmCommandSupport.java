package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * ASDM 工具集分类元数据的序列化与校验。
 * <p>{@code asdmCommands[].toolName} 表示内层 ZIP 中 {@code .asdm/toolsets/} 下的一级目录名
 * （工具集名），不是 {@code actions/*.md} 命令名。接收端只做格式与同数组去重校验；
 * 工具集是否真实存在以及分类是否合法，只在管理员导入时校验。
 */
final class AsdmCommandSupport {
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    static final int MAX_COMMANDS = 2000;
    static final int MAX_TOOL_NAME_LENGTH = 128;
    static final int MAX_CATEGORY_CODE_LENGTH = 64;

    private AsdmCommandSupport() {}

    /** 解析后的工具集分类声明。 */
    record ToolsetCategory(String toolsetName, String firstCategoryCode, String developmentStage) {}

    /** 按字段与数组原有顺序做 JSON 规范化序列化；空白差异不会改变哈希。 */
    static String canonicalJson(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize ASDM command metadata", ex);
        }
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * 接收阶段校验：数组结构、条目类型、工具集名称格式与同数组去重。
     * 不查询分类树，不展开内层 ZIP，不写映射表。
     */
    static void validateIncomingToolsets(JsonNode root) {
        if (root == null || root.isNull()) return;
        if (!root.isArray() || root.size() > MAX_COMMANDS)
            throw invalid("ASDM_TOOLSET_METADATA_INVALID", "asdmCommands 必须是 JSON 数组且不超过 " + MAX_COMMANDS + " 项");
        Map<String, Integer> firstPosition = new HashMap<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            int index = i + 1;
            if (item == null || !item.isObject())
                throw invalid("ASDM_TOOLSET_METADATA_INVALID", "第 " + index + " 项不是对象");
            String toolsetName = requireValidToolsetName(item.get("toolName"), index);
            Integer first = firstPosition.putIfAbsent(duplicateKey(toolsetName), index);
            if (first != null)
                throw invalid("ASDM_TOOLSET_DUPLICATE",
                        "工具集 " + toolsetName + " 在第 " + first + " 和第 " + index + " 项重复");
            JsonNode category = item.get("firstCategoryCode");
            if (category != null && !category.isNull()
                    && (!category.isTextual() || category.asText().trim().length() > MAX_CATEGORY_CODE_LENGTH))
                throw invalid("ASDM_TOOLSET_METADATA_INVALID", "第 " + index + " 项 firstCategoryCode 无效");
        }
    }

    /**
     * 管理员导入阶段解析。先复用接收阶段的格式与去重规则，再校验一级分类是否有效。
     * resolver 返回一级分类对应的 DevelopmentStage 枚举名；返回 null 表示分类无效。
     */
    static List<ToolsetCategory> parse(JsonNode root, Function<String, String> resolver) {
        if (root == null || root.isNull()) return List.of();
        validateIncomingToolsets(root);
        List<ToolsetCategory> toolsets = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            int index = i + 1;
            String toolsetName = requireValidToolsetName(item.get("toolName"), index);
            String categoryCode = text(item.get("firstCategoryCode"));
            if (categoryCode == null || categoryCode.length() > MAX_CATEGORY_CODE_LENGTH)
                throw invalid("ASDM_FIRST_CATEGORY_INVALID", "工具集 " + toolsetName + " 的 firstCategoryCode 无效");
            String stage = resolver.apply(categoryCode);
            if (stage == null)
                throw invalid("ASDM_FIRST_CATEGORY_INVALID",
                        "工具集 " + toolsetName + " 的 firstCategoryCode 不是有效的一级分类：" + categoryCode);
            toolsets.add(new ToolsetCategory(toolsetName, categoryCode, stage));
        }
        return List.copyOf(toolsets);
    }

    /** 用于审计与状态展示的条目数：非数组按 0 处理。 */
    static int count(JsonNode root) {
        return root != null && root.isArray() ? root.size() : 0;
    }

    private static String requireValidToolsetName(JsonNode node, int index) {
        String name = text(node);
        if (name == null || name.isEmpty() || name.length() > MAX_TOOL_NAME_LENGTH || !isValidToolsetName(name))
            throw invalid("ASDM_TOOLSET_NAME_INVALID", "第 " + index + " 项 toolName 不是合法的工具集目录名");
        return name;
    }

    private static boolean isValidToolsetName(String name) {
        if (".".equals(name) || "..".equals(name)) return false;
        return name.chars().noneMatch(c -> c == '/' || c == '\\' || c < 0x20 || c == 0x7f);
    }

    /** 去空白 + Unicode NFC + Locale.ROOT 小写，避免大小写/规范化造成的等价重复。 */
    private static String duplicateKey(String name) {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return node.asText().trim();
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}