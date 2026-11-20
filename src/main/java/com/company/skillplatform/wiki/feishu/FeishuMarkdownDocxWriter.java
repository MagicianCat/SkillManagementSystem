package com.company.skillplatform.wiki.feishu;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Writes the Markdown subset that maps to native DOCX blocks, including tables. */
@Component
public class FeishuMarkdownDocxWriter {
    private static final Pattern HEADING = Pattern.compile("^(#{1,9})\\s+(.+)$");
    private static final Pattern TODO = Pattern.compile("^[-*+]\\s+\\[([ xX])]\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^[0-9]+[.)]\\s+(.+)$");
    private static final Pattern INLINE = Pattern.compile("(?s)(\\*\\*\\*.+?\\*\\*\\*|___.+?___|\\*\\*.+?\\*\\*|__.+?__|~~.+?~~|`[^`]+`|\\[[^]]+]\\(https?://[^)]+\\)|<https?://[^>]+>|<u>.+?</u>|\\*[^*]+\\*|_[^_]+_)");
    private final ObjectMapper json;
    private final FeishuDriveGateway drive;
    private final FeishuDocumentMcpProxy proxy;

    public FeishuMarkdownDocxWriter(ObjectMapper json, FeishuDriveGateway drive, FeishuDocumentMcpProxy proxy) {
        this.json = json; this.drive = drive; this.proxy = proxy;
    }

    public void replace(long actor, String token, String markdown) {
        List<Step> plan = parse(markdown);
        proxy.replaceNativeDocx(actor, token, "");
        List<JsonNode> pending = new ArrayList<>();
        List<String> listParents = new ArrayList<>();
        for (Step step : plan) {
            if (step instanceof TextStep text) {
                if (text.list()) {
                    flush(actor, token, pending);
                    while (listParents.size() > text.listDepth()) listParents.remove(listParents.size() - 1);
                    if (text.listDepth() > listParents.size()) throw failed("Markdown 列表层级不连续");
                    String parent = text.listDepth() == 0 ? token : listParents.get(text.listDepth() - 1);
                    String blockId = drive.createChildren(actor, token, parent, List.of(text.block()))
                            .path("children").path(0).path("block_id").asText("");
                    if (blockId.isBlank()) throw failed("飞书未返回列表块标识，需核对导出结果");
                    listParents.add(blockId);
                } else {
                    listParents.clear();
                    pending.add(text.block());
                    if (pending.size() == 50) flush(actor, token, pending);
                }
            } else if (step instanceof TableStep table) {
                listParents.clear();
                flush(actor, token, pending);
                writeTable(actor, token, table.rows());
            }
        }
        flush(actor, token, pending);
    }

    List<Step> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) throw failed("Wiki 正文为空");
        if (Pattern.compile("(?m)!\\[[^]]*]\\([^)]*\\)|<img\\b|<table\\b", Pattern.CASE_INSENSITIVE).matcher(markdown).find())
            throw failed("图片或 HTML 表格无法安全写入飞书 DOCX，请选择 Markdown 文件格式");
        String[] lines = markdown.split("\\R", -1);
        List<Step> result = new ArrayList<>();
        List<Integer> listIndents = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                boolean closed = false;
                for (i++; i < lines.length; i++) {
                    if (lines[i].trim().startsWith("```")) { closed = true; break; }
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                }
                if (!closed) throw failed("Markdown 代码围栏未闭合");
                result.add(new TextStep(codeBlock(code.toString())));
                continue;
            }
            if (looksLikeTable(trimmed, i + 1 < lines.length ? lines[i + 1] : "")) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(splitRow(line));
                int width = rows.get(0).size();
                i += 2;
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    List<String> row = splitRow(lines[i]);
                    if (row.size() != width) throw failed("Markdown 表格列数不一致");
                    rows.add(row);
                    i++;
                }
                i--;
                result.add(new TableStep(rows));
                continue;
            }
            Matcher match;
            if ((match = HEADING.matcher(trimmed)).matches()) result.add(new TextStep(textBlock(match.group(1).length() + 2, "heading" + match.group(1).length(), match.group(2), false)));
            else if ((match = TODO.matcher(trimmed)).matches()) result.add(new TextStep(textBlock(17, "todo", match.group(2), !" ".equals(match.group(1))), listDepth(line, listIndents), true));
            else if ((match = BULLET.matcher(trimmed)).matches()) result.add(new TextStep(textBlock(12, "bullet", match.group(1), false), listDepth(line, listIndents), true));
            else if ((match = ORDERED.matcher(trimmed)).matches()) result.add(new TextStep(textBlock(13, "ordered", match.group(1), false), listDepth(line, listIndents), true));
            else if (trimmed.matches("(?:-{3,}|\\*{3,}|_{3,})")) result.add(new TextStep(json.createObjectNode().put("block_type", 22).set("divider", json.createObjectNode())));
            else if (trimmed.startsWith(">")) result.add(new TextStep(textBlock(15, "quote", trimmed.replaceFirst("^>\\s?", ""), false)));
            else result.add(new TextStep(textBlock(2, "text", line, false)));
            if (!(result.get(result.size() - 1) instanceof TextStep step && step.list())) listIndents.clear();
            if (result.size() > 5000) throw failed("Markdown 块数量过多");
        }
        return result;
    }

    private static boolean looksLikeTable(String header, String separator) {
        return header.startsWith("|") && separator.trim().matches("\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?");
    }
    private static int listDepth(String line, List<Integer> indents) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
        if (spaces % 2 != 0) throw failed("Markdown 列表缩进须为 2 的倍数");
        while (!indents.isEmpty() && indents.get(indents.size() - 1) > spaces) indents.remove(indents.size() - 1);
        if (indents.isEmpty() || indents.get(indents.size() - 1) < spaces) indents.add(spaces);
        if (indents.size() > 11) throw failed("Markdown 列表最多支持 10 层嵌套");
        return indents.size() - 1;
    }
    private static List<String> splitRow(String line) {
        String value = line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == '|') { cell.append('|'); i++; }
            else if (c == '|') { cells.add(cell.toString().trim()); cell.setLength(0); }
            else cell.append(c);
        }
        cells.add(cell.toString().trim());
        if (cells.size() > 50) throw failed("Markdown 表格列数超过限制");
        return cells;
    }

    private ObjectNode codeBlock(String content) {
        ObjectNode block = textBlock(14, "code", content, false);
        block.path("code");
        ((ObjectNode) block.path("code")).putObject("style").put("language", 1);
        return block;
    }
    private ObjectNode textBlock(int type, String key, String content, boolean done) {
        ObjectNode block = json.createObjectNode().put("block_type", type);
        ObjectNode body = block.putObject(key);
        ArrayNode elements = body.putArray("elements");
        if (type == 14) elements.addObject().putObject("text_run").put("content", content);
        else inlineElements(content, elements);
        if (type == 17) body.putObject("style").put("done", done);
        return block;
    }
    private void inlineElements(String value, ArrayNode elements) {
        Matcher matcher = INLINE.matcher(value);
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() > offset) run(elements, value.substring(offset, matcher.start()), false, false, false, false, false, null);
            String token = matcher.group();
            if (token.startsWith("[")) {
                int split = token.indexOf("](");
                run(elements, token.substring(1, split), false, false, false, false, false, token.substring(split + 2, token.length() - 1));
            } else if (token.startsWith("***")) run(elements, token.substring(3, token.length() - 3), true, true, false, false, false, null);
            else if (token.startsWith("___")) run(elements, token.substring(3, token.length() - 3), true, true, false, false, false, null);
            else if (token.startsWith("**")) run(elements, token.substring(2, token.length() - 2), true, false, false, false, false, null);
            else if (token.startsWith("__")) run(elements, token.substring(2, token.length() - 2), true, false, false, false, false, null);
            else if (token.startsWith("~~")) run(elements, token.substring(2, token.length() - 2), false, false, true, false, false, null);
            else if (token.startsWith("<https://") || token.startsWith("<http://")) {
                String link = token.substring(1, token.length() - 1);
                run(elements, link, false, false, false, false, false, link);
            }
            else if (token.startsWith("<u>")) run(elements, token.substring(3, token.length() - 4), false, false, false, true, false, null);
            else if (token.startsWith("`")) run(elements, token.substring(1, token.length() - 1), false, false, false, false, true, null);
            else run(elements, token.substring(1, token.length() - 1), false, true, false, false, false, null);
            offset = matcher.end();
        }
        if (offset < value.length()) run(elements, value.substring(offset), false, false, false, false, false, null);
        if (elements.isEmpty()) run(elements, "", false, false, false, false, false, null);
    }
    private void run(ArrayNode elements, String content, boolean bold, boolean italic, boolean strike, boolean underline, boolean code, String link) {
        ObjectNode run = elements.addObject().putObject("text_run").put("content", content);
        ObjectNode style = run.putObject("text_element_style");
        if (bold) style.put("bold", true);
        if (italic) style.put("italic", true);
        if (strike) style.put("strikethrough", true);
        if (underline) style.put("underline", true);
        if (code) style.put("inline_code", true);
        if (link != null) style.putObject("link").put("url", link);
    }

    private void flush(long actor, String token, List<JsonNode> pending) {
        if (pending.isEmpty()) return;
        drive.createChildren(actor, token, token, pending);
        pending.clear();
    }
    private void writeTable(long actor, String token, List<List<String>> rows) {
        int columns = rows.get(0).size();
        if (rows.size() > 500 || columns == 0 || columns > 50) throw failed("Markdown 表格行列超出限制");
        ObjectNode table = json.createObjectNode().put("block_type", 31);
        table.putObject("table").putObject("property").put("row_size", rows.size()).put("column_size", columns);
        JsonNode created = drive.createChildren(actor, token, token, List.of(table));
        JsonNode cells = created.path("children").path(0).path("table").path("cells");
        if (!cells.isArray() || cells.size() != rows.size() * columns) throw failed("飞书未返回完整表格单元格，需核对导出结果");
        for (int row = 0; row < rows.size(); row++) for (int column = 0; column < columns; column++) {
            String cellId = cells.get(row * columns + column).asText("");
            if (cellId.isBlank()) throw failed("飞书表格单元格标识缺失");
            drive.createChildren(actor, token, cellId, List.of(textBlock(2, "text", rows.get(row).get(column).replace("<br>", "\n"), false)));
        }
    }
    sealed interface Step permits TextStep, TableStep {}
    record TextStep(ObjectNode block, int listDepth, boolean list) implements Step {
        TextStep(ObjectNode block) { this(block, 0, false); }
    }
    record TableStep(List<List<String>> rows) implements Step {}
    private static BusinessException failed(String message) { return new BusinessException("FEISHU_DOCUMENT_CONVERT_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY); }
}
