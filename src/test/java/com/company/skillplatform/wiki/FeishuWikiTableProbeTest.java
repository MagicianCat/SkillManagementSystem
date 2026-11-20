package com.company.skillplatform.wiki;

import com.company.skillplatform.wiki.feishu.FeishuDriveGateway;
import com.company.skillplatform.wiki.feishu.FeishuDocxMarkdownConverter;
import com.company.skillplatform.wiki.feishu.WikiFeishuTransferService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.table.probe.user-id", matches = "[0-9]+")
class FeishuWikiTableProbeTest {
    @Autowired FeishuDriveGateway drive;
    @Autowired FeishuDocxMarkdownConverter converter;
    @Autowired WikiFeishuTransferService transfers;
    @Test void inspectBlockShapeWithoutDocumentText() {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.table.probe.user-id"));
        var source = drive.resolve(actor, "WIKI", "NubqwIGF5iOSdckM2BBclLBWnsf");
        System.out.println("FEISHU_TABLE_PROBE type=" + source.type() + " documentToken=" + source.token());
        var blocks = drive.blocks(actor, source.token());
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (JsonNode block : blocks) counts.merge(block.path("block_type").asInt(-1), 1, Integer::sum);
        System.out.println("FEISHU_TABLE_PROBE counts=" + counts);
        for (JsonNode block : blocks) if (block.path("block_type").asInt() == 31) {
            java.util.List<String> tableKeys = new java.util.ArrayList<>();
            block.path("table").fieldNames().forEachRemaining(tableKeys::add);
            System.out.println("FEISHU_TABLE_PROBE tableKeys=" + tableKeys);
            System.out.println("FEISHU_TABLE_PROBE tableRows=" + block.path("table").path("property").path("row_size").asInt(-1)
                    + " columns=" + block.path("table").path("property").path("column_size").asInt(-1)
                    + " children=" + block.path("children").size() + " cells=" + block.path("table").path("cells").size());
        }
        String markdown = converter.convert(blocks);
        long tables = markdown.lines().filter(line -> line.startsWith("| --- |")).count();
        System.out.println("FEISHU_TABLE_PROBE convertedLength=" + markdown.length() + " markdownTables=" + tables);
        org.junit.jupiter.api.Assertions.assertEquals(4, tables);
        var resolved = transfers.resolveUrl(actor, "https://tkhome.feishu.cn/wiki/NubqwIGF5iOSdckM2BBclLBWnsf");
        var editable = transfers.editableContent(actor, new WikiFeishuTransferService.Source(resolved.type(), resolved.token()));
        org.junit.jupiter.api.Assertions.assertEquals(markdown, editable.markdownContent());
    }
}
