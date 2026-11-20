package com.company.skillplatform.wiki.feishu;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeishuMarkdownDocxWriterTest {
    private final FeishuMarkdownDocxWriter writer = new FeishuMarkdownDocxWriter(new ObjectMapper(), null, null);

    @Test void plansNativeBlocksForMarkdownComponents() {
        var steps = writer.parse("# 标题\n\n__加粗__ _斜体_ <https://feishu.cn>\n\n- [x] 完成\n\n---\n\n| A | B |\n| --- | --- |\n| x | y |\n");
        assertEquals(5, steps.size());
        assertEquals(3, ((FeishuMarkdownDocxWriter.TextStep) steps.get(0)).block().path("block_type").asInt());
        var inline = ((FeishuMarkdownDocxWriter.TextStep) steps.get(1)).block().path("text").path("elements");
        assertTrue(inline.toString().contains("\"bold\":true"));
        assertTrue(inline.toString().contains("\"italic\":true"));
        assertTrue(inline.toString().contains("\"link\""));
        assertEquals(17, ((FeishuMarkdownDocxWriter.TextStep) steps.get(2)).block().path("block_type").asInt());
        assertEquals(22, ((FeishuMarkdownDocxWriter.TextStep) steps.get(3)).block().path("block_type").asInt());
        assertEquals(2, ((FeishuMarkdownDocxWriter.TableStep) steps.get(4)).rows().size());
    }

    @Test void rejectsMediaThatCannotBeWrittenWithoutLoss() {
        assertThrows(BusinessException.class, () -> writer.parse("![图片](https://example.com/image.png)"));
    }

    @Test void retainsNestedListDepthInPlan() {
        var steps = writer.parse("- 父项\n  - 子项\n    - 孙项\n");
        assertEquals(0, ((FeishuMarkdownDocxWriter.TextStep) steps.get(0)).listDepth());
        assertEquals(1, ((FeishuMarkdownDocxWriter.TextStep) steps.get(1)).listDepth());
        assertEquals(2, ((FeishuMarkdownDocxWriter.TextStep) steps.get(2)).listDepth());
    }
}
