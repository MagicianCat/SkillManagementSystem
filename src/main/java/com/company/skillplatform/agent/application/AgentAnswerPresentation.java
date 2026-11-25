package com.company.skillplatform.agent.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats assistant text for display while preserving the destinations of citations. */
public final class AgentAnswerPresentation {
    private static final Pattern URL = Pattern.compile("(?:https?://[^\\s<>`\\[\\]()]+|/(?:wiki\\?|asdm-tools/download/|agent\\?)[^\\s<>`\\[\\]()]+)");
    private static final Pattern ID = Pattern.compile(
            "(?i)(?:\\*{1,2}|`)?(?:wiki(?:[ \\t]*文档)?|文档|角色|工具包|资源|修订|版本|任务|目录)[ \\t]*(?:id|编号|#)[ \\t]*(?:\\*{1,2}|`)?[ \\t]*[:：=]?\\s*(?:\\*{1,2}|`)?[ \\t]*[0-9]+(?:\\*{1,2}|`)?"
            +"|(?i)(?:\\*{1,2}|`)?(?:externalRoleIds?|wikiId|documentId|roleIds?|assetId|resourceId|revisionId|versionId|taskId|runId|catalogId)[ \\t]*(?:\\*{1,2}|`)?[ \\t]*[:：=#]?\\s*(?:\\*{1,2}|`)?(?:\\[[0-9, \\t]+\\]|[0-9]+|t[0-9]+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(?:\\*{1,2}|`)?");
    private static final Map<String,String> LABELS = Map.ofEntries(
            Map.entry("FDE_WIKI","FDE 专区"),Map.entry("PROJECT_CONTEXT","项目上下文"),
            Map.entry("INTERNAL_KNOWLEDGE","内部知识"),Map.entry("SKILL_TOOL","Skill 与工具"),
            Map.entry("OTHER","其他知识"),Map.entry("BACKEND_CODING","后端编码"),
            Map.entry("PLATFORM_PUBLIC_ONLY","仅平台公开资料"),Map.entry("USER_VISIBLE","当前可见资料"),
            Map.entry("PLATFORM","平台发布版"),Map.entry("TEAM","团队版"),
            Map.entry("STRICT","严格匹配"),Map.entry("PREFER","优先匹配"),
            Map.entry("GENERAL_FALLBACK","通用知识"),Map.entry("CATEGORY_FALLBACK","补充分类"),
            Map.entry("MATCHED","已匹配"),Map.entry("REQUIRED","必选"),Map.entry("RECOMMENDED","推荐"),
            Map.entry("OPTIONAL","可选"),Map.entry("RETRIEVAL_SCOPE_CONFLICT","超出本次检索范围"),
            Map.entry("RETRIEVAL_VERSION_CHANGED","文档版本已变化"),Map.entry("ROLE_INDEX_PENDING","索引同步中"),
            Map.entry("SEMANTIC_UNAVAILABLE","语义检索暂不可用"),
            Map.entry("search_knowledge","知识检索"),Map.entry("get_wiki_document","读取 Wiki"),
            Map.entry("list_knowledge_catalog","查询资料目录"),Map.entry("resolve_external_roles","查找角色"),
            Map.entry("get_retrieval_plan","确认检索范围"));
    private static final Pattern LABEL = Pattern.compile("(?<![\\p{Alnum}_])(?:"
            +String.join("|",LABELS.keySet())+")(?![\\p{Alnum}_])");
    private AgentAnswerPresentation() {}

    public static String clean(String value) {
        if(value==null||value.isEmpty())return value;
        value=value.replaceAll("`((?:https?://[^\\s`]+)?/(?:wiki\\?|asdm-tools/download/|agent\\?)[^\\s`]+)`","$1");
        var urls=URL.matcher(value);
        var output=new StringBuilder();
        int last=0;
        while(urls.find()){
            output.append(prose(value.substring(last,urls.start())));
            String url=urls.group();
            boolean markdownDestination=urls.start()>=2&&value.substring(urls.start()-2,urls.start()).equals("](");
            boolean wiki=url.contains("/wiki?")&&url.contains("documentId=");
            boolean asdm=url.contains("/asdm-tools/download/");
            boolean catalog=url.contains("/agent?")&&url.contains("catalogRun=");
            if(markdownDestination&&output.toString().endsWith("[](")){
                output.setLength(output.length()-3);
                output.append(wiki?"[查看 Wiki 原文](":asdm?"[下载工具包](":"[查看资料](");
            }
            if(markdownDestination||!(wiki||asdm||catalog))output.append(url);
            else output.append(wiki?"[查看 Wiki 原文](":asdm?"[下载工具包](":"[查看资源目录](").append(url).append(')');
            last=urls.end();
        }
        return output.append(prose(value.substring(last))).toString();
    }

    public static Map<String,Object> recommendationItem(Map<String,Object> input) {
        var item=new java.util.LinkedHashMap<>(input);
        for(String field:java.util.List.of("reason","description","displayName")){
            if(item.get(field) instanceof String text)item.put(field,clean(text));
        }
        return item;
    }

    private static String prose(String text) {
        String cleaned=ID.matcher(text).replaceAll("");
        cleaned=LABEL.matcher(cleaned).replaceAll(match->Matcher.quoteReplacement(LABELS.get(match.group())));
        return cleaned.replaceAll("[（(][ \\t]*[）)]","")
                .replaceAll("[（(][ \\t]*[，,；;][ \\t]*","（");
    }
}
