package com.company.skillplatform.wiki.feishu;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Converts document blocks without silently dropping content-bearing components. */
@Component
public class FeishuDocxMarkdownConverter {
    public String convert(List<JsonNode> blocks) {
        if (blocks == null || blocks.isEmpty()) throw failed("飞书文档没有可读取的 blocks");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode block : blocks) byId.put(block.path("block_id").asText(""), block);
        JsonNode root = blocks.stream().filter(block -> block.path("block_type").asInt() == 1).findFirst().orElse(null);
        if (root == null) throw failed("飞书文档缺少根 block");
        List<String> paragraphs = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        render(root, byId, visited, paragraphs, 0, 0);
        if (visited.size() != blocks.size()) throw failed("飞书文档存在未解析的嵌套内容");
        StringBuilder assembled = new StringBuilder();
        String previous = "";
        for (String paragraph : paragraphs) {
            if (assembled.length() > 0) assembled.append(isList(previous) && isList(paragraph) ? "\n" : "\n\n");
            assembled.append(paragraph);
            previous = paragraph;
        }
        String result = assembled.toString().strip();
        if (result.isEmpty()) throw failed("飞书文档正文为空");
        if (result.length() > 1_000_000) throw failed("飞书文档超过 Wiki 正文上限");
        return result + "\n";
    }

    private static boolean isList(String value) { return value.matches("(?s)^\\s*(?:[-*+] |[0-9]+\\. ).*"); }

    private void render(JsonNode block, Map<String, JsonNode> byId, Set<String> visited, List<String> out, int depth, int listDepth) {
        if (depth > 20) throw failed("飞书文档层级过深");
        String id = block.path("block_id").asText("");
        if (id.isBlank() || !visited.add(id)) throw failed("飞书文档 block 重复或缺少标识");
        int type = block.path("block_type").asInt(-1);
        if (type == 31) {
            renderTable(block, byId, visited, out, depth);
            return;
        }
        if (type == 32 || type == 24 || type == 25 || type == 33) {
            renderChildren(block, byId, visited, out, depth, listDepth);
            return;
        }
        if (type == 19 || type == 34) {
            List<String> inner = new ArrayList<>();
            renderChildren(block, byId, visited, inner, depth, 0);
            if (!inner.isEmpty()) {
                String label = type == 19 ? "**提示**\n> " : "";
                out.add("> " + label + String.join("\n\n", inner).replace("\n", "\n> "));
            }
            return;
        }
        if (type == 22) { out.add("---"); renderChildren(block, byId, visited, out, depth, listDepth); return; }
        if (type == 16) {
            String equation = block.path("equation").path("content").asText("");
            if (equation.isBlank()) throw failed("飞书公式正文不可读取");
            out.add("$$\n" + equation + "\n$$");
            renderChildren(block, byId, visited, out, depth, listDepth);
            return;
        }
        if (type == 23) {
            String token = block.path("file").path("token").asText("");
            String name = block.path("file").path("name").asText("飞书附件");
            if (token.isBlank()) throw failed("飞书附件缺少可引用标识");
            out.add("[" + escapeInline(name) + "](https://feishu.cn/file/" + token + ")");
            renderChildren(block, byId, visited, out, depth, listDepth);
            return;
        }
        if (type == 26) {
            String url = block.path("iframe").path("component").path("url").asText("");
            if (url.isBlank()) url = block.path("iframe").path("url").asText("");
            if (!url.startsWith("https://")) throw failed("飞书内嵌页面缺少安全链接");
            out.add("[内嵌页面](" + url.replace(")", "%29") + ")");
            renderChildren(block, byId, visited, out, depth, listDepth);
            return;
        }
        if (type != 1) {
            String text = text(block, type);
            if (!text.isBlank()) out.add(switch (type) {
                case 2 -> text;
                case 3, 4, 5, 6, 7, 8, 9, 10, 11 -> "#".repeat(type - 2) + " " + text;
                case 12 -> "  ".repeat(listDepth) + "- " + text;
                case 13 -> "  ".repeat(listDepth) + "1. " + text;
                case 14 -> "`".repeat(Math.max(3, longestBacktickRun(text) + 1)) + "\n" + text + "\n"
                        + "`".repeat(Math.max(3, longestBacktickRun(text) + 1));
                case 15 -> "> " + text.replace("\n", "\n> ");
                case 17 -> "  ".repeat(listDepth) + "- [" + (block.path("todo").path("style").path("done").asBoolean(false) ? "x" : " ") + "] " + text;
                default -> throw failed("飞书文档包含暂不支持的块类型：" + type);
            });
        }
        renderChildren(block, byId, visited, out, depth, listDepth + (type == 12 || type == 13 || type == 17 ? 1 : 0));
    }

    private void renderTable(JsonNode table, Map<String, JsonNode> byId, Set<String> visited, List<String> out, int depth) {
        int rows = table.path("table").path("property").path("row_size").asInt(-1);
        int columns = table.path("table").path("property").path("column_size").asInt(-1);
        JsonNode cells = table.path("table").path("cells");
        if (rows <= 0 || columns <= 0 || rows > 500 || columns > 50 || !cells.isArray() || cells.size() != rows * columns)
            throw failed("飞书表格行列结构不完整");
        if (table.path("children").size() != cells.size()) throw failed("飞书表格单元格不完整");
        List<List<String>> lines = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                String cellId = cells.get(row * columns + column).asText("");
                JsonNode cell = byId.get(cellId);
                if (cell == null || cell.path("block_type").asInt() != 32) throw failed("飞书表格单元格缺失");
                List<String> cellContent = new ArrayList<>();
                render(cell, byId, visited, cellContent, depth + 1, 0);
                values.add(String.join("<br>", cellContent).replace("|", "\\|").replace("\n", "<br>"));
            }
            lines.add(values);
        }
        List<String> markdown = new ArrayList<>();
        markdown.add(markdownRow(lines.get(0)));
        markdown.add("|" + " --- |".repeat(columns));
        for (int row = 1; row < lines.size(); row++) markdown.add(markdownRow(lines.get(row)));
        out.add(String.join("\n", markdown));
    }

    private static String markdownRow(List<String> cells) { return "| " + String.join(" | ", cells) + " |"; }

    private void renderChildren(JsonNode block, Map<String, JsonNode> byId, Set<String> visited, List<String> out, int depth, int listDepth) {
        for (JsonNode childId : block.path("children")) {
            JsonNode child = byId.get(childId.asText());
            if (child == null) throw failed("飞书文档嵌套 block 不完整");
            render(child, byId, visited, out, depth + 1, listDepth);
        }
    }

    private String text(JsonNode block, int type) {
        String key = switch (type) {
            case 2 -> "text";
            case 3, 4, 5, 6, 7, 8, 9, 10, 11 -> "heading" + (type - 2);
            case 12 -> "bullet";
            case 13 -> "ordered";
            case 14 -> "code";
            case 15 -> "quote";
            case 17 -> "todo";
            default -> throw failed("飞书文档包含暂不支持的块类型：" + type);
        };
        JsonNode elements = block.path(key).path("elements");
        if (!elements.isArray()) throw failed("飞书文档文本结构不完整");
        StringBuilder result = new StringBuilder();
        for (JsonNode element : elements) {
            JsonNode run = element.path("text_run");
            if (run.isMissingNode()) {
                JsonNode equation = element.path("equation");
                if (!equation.isMissingNode()) { result.append("$").append(equation.path("content").asText("")).append("$"); continue; }
                JsonNode mention = element.path("mention_doc");
                if (!mention.isMissingNode()) {
                    String token = mention.path("token").asText("");
                    if (token.isBlank()) throw failed("飞书文档引用缺少标识");
                    result.append("[").append(escapeInline(mention.path("title").asText("飞书文档"))).append("](https://feishu.cn/docx/").append(token).append(")");
                    continue;
                }
                JsonNode user = element.path("mention_user");
                if (!user.isMissingNode()) { result.append("@").append(escapeInline(user.path("name").asText("用户"))); continue; }
                JsonNode file = element.path("file");
                if (!file.isMissingNode()) {
                    String token = file.path("file_token").asText(file.path("token").asText(""));
                    if (token.isBlank()) throw failed("飞书附件引用缺少标识");
                    result.append("[").append(escapeInline(file.path("name").asText("飞书附件")))
                            .append("](https://feishu.cn/file/").append(token).append(")");
                    continue;
                }
                throw failed("飞书文档包含暂不支持的富文本元素");
            }
            String content = type == 14 ? run.path("content").asText("") : escapeInline(run.path("content").asText(""));
            JsonNode style = run.path("text_element_style");
            String link = style.path("link").path("url").asText("");
            if (!link.isBlank()) content = "[" + content.replace("]", "\\]") + "](" + link.replace(")", "%29") + ")";
            if (style.path("inline_code").asBoolean()) content = "`" + content.replace("`", "\\`") + "`";
            if (style.path("strikethrough").asBoolean()) content = "~~" + content + "~~";
            if (style.path("underline").asBoolean()) content = "<u>" + content + "</u>";
            if (style.path("bold").asBoolean()) content = "**" + content + "**";
            if (style.path("italic").asBoolean()) content = "*" + content + "*";
            result.append(content);
        }
        return result.toString();
    }
    private static String escapeInline(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("~", "\\~").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]");
    }
    private static int longestBacktickRun(String value) {
        int max = 0, current = 0;
        for (int i = 0; i < value.length(); i++) {
            current = value.charAt(i) == '`' ? current + 1 : 0;
            max = Math.max(max, current);
        }
        return max;
    }
    private static BusinessException failed(String message) { return new BusinessException("FEISHU_DOCUMENT_CONVERT_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY); }
}
