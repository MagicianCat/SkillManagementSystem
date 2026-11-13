package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * ASDM 命令分类元数据的序列化与导入期校验。
 * 接收端只负责规范化 JSON 与计算哈希；只有管理员导入时才调用 {@link #parse(JsonNode, Function)}。
 */
final class AsdmCommandSupport {
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    static final int MAX_COMMANDS = 2000;
    static final int MAX_TOOL_NAME_LENGTH = 128;
    static final int MAX_CATEGORY_CODE_LENGTH = 64;

    private AsdmCommandSupport() {}

    record Command(String toolName, String firstCategoryCode, String developmentStage) {}

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
     * 解析并校验命令数组。resolver 返回一级分类对应的 DevelopmentStage 枚举名；
     * 返回 null 表示该 firstCategoryCode 不是当前 ACTIVE 的一级根分类。
     */
    static List<Command> parse(JsonNode root, Function<String, String> resolver) {
        if (root == null || root.isNull()) return List.of();
        if (!root.isArray()) throw invalid("ASDM_COMMAND_MAPPING_IMPORT_FAILED", "asdmCommands 必须是 JSON 数组");
        if (root.size() > MAX_COMMANDS)
            throw invalid("ASDM_COMMAND_MAPPING_IMPORT_FAILED", "asdmCommands 条目数超过上限 " + MAX_COMMANDS);
        List<Command> commands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            int index = i + 1;
            if (item == null || !item.isObject())
                throw invalid("ASDM_COMMAND_NAME_INVALID", "第 " + index + " 项不是对象");
            String toolName = text(item.get("toolName"));
            if (toolName == null || toolName.isEmpty() || toolName.length() > MAX_TOOL_NAME_LENGTH
                    || hasControlCharacter(toolName))
                throw invalid("ASDM_COMMAND_NAME_INVALID", "第 " + index + " 项 toolName 无效");
            if (!seen.add(toolName))
                throw invalid("ASDM_COMMAND_DUPLICATE", "命令名重复：" + toolName);
            String categoryCode = text(item.get("firstCategoryCode"));
            if (categoryCode == null || categoryCode.length() > MAX_CATEGORY_CODE_LENGTH)
                throw invalid("ASDM_FIRST_CATEGORY_INVALID", "命令 " + toolName + " 的 firstCategoryCode 无效");
            String stage = resolver.apply(categoryCode);
            if (stage == null)
                throw invalid("ASDM_FIRST_CATEGORY_INVALID",
                        "命令 " + toolName + " 的 firstCategoryCode 不是有效的一级分类：" + categoryCode);
            commands.add(new Command(toolName, categoryCode, stage));
        }
        return List.copyOf(commands);
    }

    /** 用于审计与状态展示的条目数：非数组按 0 处理。 */
    static int count(JsonNode root) {
        return root != null && root.isArray() ? root.size() : 0;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return node.asText().trim();
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}