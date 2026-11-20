package com.company.skillplatform.wiki;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.wiki.feishu.FeishuDocxMarkdownConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeishuDocxMarkdownConverterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FeishuDocxMarkdownConverter converter = new FeishuDocxMarkdownConverter();
    private JsonNode block(String value) { try { return json.readTree(value); } catch (Exception ex) { throw new RuntimeException(ex); } }

    @Test void convertsTextContainersLinksListsAndDivider() {
        List<JsonNode> blocks = List.of(
                block("{\"block_id\":\"root\",\"block_type\":1,\"children\":[\"call\",\"list\",\"divider\",\"file\",\"iframe\"]}"),
                block("{\"block_id\":\"call\",\"block_type\":19,\"children\":[\"rich\"]}"),
                block("{\"block_id\":\"rich\",\"block_type\":2,\"text\":{\"elements\":[{\"text_run\":{\"content\":\"加粗\",\"text_element_style\":{\"bold\":true}}},{\"text_run\":{\"content\":\" 删除\",\"text_element_style\":{\"strikethrough\":true}}},{\"text_run\":{\"content\":\" 链接\",\"text_element_style\":{\"link\":{\"url\":\"https://www.feishu.cn\"}}}}]}}"),
                block("{\"block_id\":\"list\",\"block_type\":12,\"bullet\":{\"elements\":[{\"text_run\":{\"content\":\"父项\"}}]},\"children\":[\"child\"]}"),
                block("{\"block_id\":\"child\",\"block_type\":17,\"todo\":{\"elements\":[{\"text_run\":{\"content\":\"完成\"}}],\"style\":{\"done\":true}}}"),
                block("{\"block_id\":\"divider\",\"block_type\":22}"),
                block("{\"block_id\":\"file\",\"block_type\":23,\"file\":{\"token\":\"abc123\",\"name\":\"说明.md\"}}"),
                block("{\"block_id\":\"iframe\",\"block_type\":26,\"iframe\":{\"url\":\"https://example.com\"}}"));
        String markdown = converter.convert(blocks);
        assertTrue(markdown.contains("**加粗**"));
        assertTrue(markdown.contains("~~ 删除~~"));
        assertTrue(markdown.contains("[ 链接](https://www.feishu.cn)"));
        assertTrue(markdown.contains("- 父项\n  - [x] 完成"));
        assertTrue(markdown.contains("---"));
        assertTrue(markdown.contains("[说明.md](https://feishu.cn/file/abc123)"));
        assertTrue(markdown.contains("[内嵌页面](https://example.com)"));
    }

    @Test void refusesImageRatherThanSilentlyDroppingIt() {
        List<JsonNode> blocks = List.of(block("{\"block_id\":\"root\",\"block_type\":1,\"children\":[\"image\"]}"),
                block("{\"block_id\":\"image\",\"block_type\":27,\"image\":{\"token\":\"abc\"}}"));
        assertThrows(BusinessException.class, () -> converter.convert(blocks));
    }
}
