package com.company.skillplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

public class MarkdownHeadingChunker {
    private final int targetChars;
    private final int overlapChars;

    public MarkdownHeadingChunker(int targetChars, int overlapChars) {
        if (targetChars < 1 || overlapChars < 0 || overlapChars >= targetChars) {
            throw new IllegalArgumentException("Invalid chunk size configuration");
        }
        this.targetChars = targetChars;
        this.overlapChars = overlapChars;
    }

    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        List<Section> sections = sections(document.markdownContent());
        List<KnowledgeChunk> result = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            for (String content : split(section.content())) {
                if (content.isBlank()) continue;
                String path = String.join(" > ", section.path());
                String embedding = document.title() + (path.isBlank() ? "" : "\n" + path) + "\n\n" + content;
                result.add(new KnowledgeChunk(document.documentId(), document.revisionNo(), document.contentSha256(),
                        document.title(), document.documentType(), document.teamId(), document.platformVisible(),
                        document.status(), List.copyOf(document.skillKeys()), List.copyOf(document.developmentStages()),
                        index++, section.heading(), List.copyOf(section.path()), content, embedding));
            }
        }
        return result;
    }

    private List<Section> sections(String markdown) {
        List<Section> result = new ArrayList<>();
        List<String> path = new ArrayList<>();
        String heading = "";
        StringBuilder content = new StringBuilder();
        boolean fence = false;
        for (String line : markdown.split("\\R", -1)) {
            if (line.stripLeading().startsWith("```")) fence = !fence;
            int level = fence ? 0 : headingLevel(line);
            if (level > 0) {
                addSection(result, heading, path, content);
                String value = line.substring(level).trim();
                while (path.size() >= level) path.remove(path.size() - 1);
                path.add(value);
                heading = value;
                content = new StringBuilder();
            } else {
                if (!content.isEmpty()) content.append('\n');
                content.append(line);
            }
        }
        addSection(result, heading, path, content);
        if (result.isEmpty()) result.add(new Section("", List.of(), markdown));
        return result;
    }

    private void addSection(List<Section> result, String heading, List<String> path, StringBuilder content) {
        String value = content.toString().trim();
        if (!value.isBlank()) result.add(new Section(heading, List.copyOf(path), value));
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && level < 3 && line.charAt(level) == '#') level++;
        return level > 0 && line.length() > level && Character.isWhitespace(line.charAt(level)) ? level : 0;
    }

    private List<String> split(String content) {
        if (content.length() <= targetChars) return List.of(content);
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + targetChars);
            if (end < content.length()) {
                int boundary = Math.max(content.lastIndexOf('\n', end), content.lastIndexOf('。', end));
                if (boundary > start + targetChars / 2) end = boundary + 1;
            }
            result.add(content.substring(start, end));
            if (end == content.length()) break;
            start = Math.max(start + 1, end - overlapChars);
        }
        return result;
    }

    private record Section(String heading, List<String> path, String content) {}
}
