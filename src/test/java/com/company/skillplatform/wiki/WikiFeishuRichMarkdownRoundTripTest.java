package com.company.skillplatform.wiki;

import com.company.skillplatform.wiki.feishu.FeishuDocxMarkdownConverter;
import com.company.skillplatform.wiki.feishu.FeishuDriveGateway;
import com.company.skillplatform.wiki.feishu.FeishuMarkdownDocxWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: creates one real DOCX in the test user's personal Drive. */
@SpringBootTest
@EnabledIfSystemProperty(named = "wiki.feishu.roundtrip.user-id", matches = "[0-9]+")
class WikiFeishuRichMarkdownRoundTripTest {
    @Autowired FeishuDriveGateway drive;
    @Autowired FeishuMarkdownDocxWriter writer;
    @Autowired FeishuDocxMarkdownConverter converter;

    @Test void richMarkdownRoundTripsThroughNativeBlocks() {
        long actor = Long.parseLong(System.getProperty("wiki.feishu.roundtrip.user-id"));
        String source = "# 飞书互转验证\n\n**加粗** *斜体* ~~删除线~~ `代码` [飞书](https://www.feishu.cn)\n\n"
                + "- 无序列表\n  - 子列表\n1. 有序列表\n- [x] 已完成\n- [ ] 待办\n\n> 引用内容\n\n---\n\n"
                + "| 名称 | 内容 |\n| --- | --- |\n| A | 第一行 |\n| B | 第二行 |\n\n"
                + "```\npublic class Example {}\n```\n";
        String token = drive.createDocx(actor, null, "研枢 Wiki 飞书富文本互转验证-" + System.currentTimeMillis());
        System.out.println("FEISHU_ROUNDTRIP url=https://feishu.cn/docx/" + token);
        writer.replace(actor, token, source);
        String result = drive.read(actor, "DOCX", token, converter);
        assertTrue(result.contains("# 飞书互转验证"));
        assertTrue(result.contains("**加粗**"));
        assertTrue(result.contains("*斜体*"));
        assertTrue(result.contains("~~删除线~~"));
        assertTrue(result.contains("`代码`"));
        assertTrue(result.contains("[飞书](https://www.feishu.cn)"));
        assertTrue(result.contains("- [x] 已完成"));
        assertTrue(result.contains("- 无序列表\n  - 子列表"));
        assertTrue(result.contains("| 名称 | 内容 |"));
        assertTrue(result.contains("| B | 第二行 |"));
        assertTrue(result.contains("public class Example {}"));
        System.out.println("FEISHU_ROUNDTRIP success length=" + result.length());
    }
}
