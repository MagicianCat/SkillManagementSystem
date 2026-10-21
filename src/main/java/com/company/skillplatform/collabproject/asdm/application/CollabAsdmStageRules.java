package com.company.skillplatform.collabproject.asdm.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Conservative, versioned rules for ASDM output. Unknown task formats never imply completion. */
@Component
public class CollabAsdmStageRules {
    public static final String VERSION = "v3.0.1";
    private static final Set<String> ENABLED = Set.of("REQUIREMENT", "PRODUCT", "ARCHITECTURE_DESIGN",
            "UI_DESIGN", "FRONTEND_CODING", "BACKEND_CODING", "TESTING");
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of("toolsets", "contexts", "spec", "extensions",
            "review-standards", "node_modules", "target", "build", ".git", "templates", "template", "cache");
    private static final Set<String> SECURITY_EXCLUDED_SEGMENTS = Set.of("node_modules", "target", "build", ".git");
    private static final Pattern TASK_ROW = Pattern.compile("(?im)^[ \\t]*\\|[ \\t]*([^|\\r\\n]+?)[ \\t]*\\|[ \\t]*(?:[^|\\r\\n]*\\|){0,5}[ \\t]*(TODO|PENDING|IN_PROGRESS|DOING|DONE|COMPLETED|BLOCKED|FAILED)[ \\t]*\\|[^\\r\\n]*$");
    private static final Pattern TASK_KV = Pattern.compile("(?im)^\\s*(?:[-*]\\s*)?(?:task[_ -]?id|任务(?:编号|ID))\\s*[:：]\\s*([A-Za-z0-9_-]+).*?(?:\\R|$)(?:.*?\\R){0,4}?\\s*(?:status|状态)\\s*[:：]\\s*(TODO|PENDING|IN_PROGRESS|DOING|DONE|COMPLETED|BLOCKED|FAILED)\\b");

    public boolean enabled(String stageKey) { return ENABLED.contains(stageKey); }

    /** Stage-specific path rules. Marker semantics remain owned by this class. */
    public record StageRuleConfig(String stageKey, boolean enabled, List<String> includeGlobs,
            List<String> excludeGlobs, List<String> markerGlobs, List<List<String>> markerGroups, String relativeBase) {
        public StageRuleConfig {
            includeGlobs = includeGlobs == null ? List.of() : List.copyOf(includeGlobs);
            excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
            markerGlobs = markerGlobs == null ? List.of() : List.copyOf(markerGlobs);
            markerGroups = markerGroups == null ? List.of() : markerGroups.stream().map(List::copyOf).toList();
        }
    }

    /** Path-only prefilter used before fetching blob contents from GitLab. */
    public boolean mayMatchStagePath(String stageKey,String requirementPath,String path) {
        return mayMatchStagePath(stageKey, requirementPath, path, null);
    }

    public boolean mayMatchStagePath(String stageKey,String requirementPath,String path, StageRuleConfig config) {
        if (!enabled(stageKey) || path == null || !path.startsWith("workspace/") || path.length() > 1600
                || path.contains("..") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return false;
        if (config != null && (!stageKey.equals(config.stageKey()) || !config.enabled())) return false;
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".gitignore")||basename(lower).startsWith("readme")||basename(lower).startsWith(".")
                ||Set.of("features-list.md","completion_report.md").contains(basename(lower)))return false;
        if (wrongCodingStage(stageKey, lower) || codingTaskOutsideCodingStage(stageKey, lower)) return false;
        for(String segment:lower.split("/"))if((config==null?EXCLUDED_SEGMENTS:SECURITY_EXCLUDED_SEGMENTS).contains(segment))return false;
        if (config != null && config.excludeGlobs().stream().anyMatch(g -> globMatches(g, path, requirementPath, config.relativeBase()))) return false;
        return config == null
                ? stagePattern(stageKey,lower,normalizedRequirement(requirementPath).toLowerCase(Locale.ROOT))
                : config.includeGlobs().stream().anyMatch(g -> globMatches(g, path, requirementPath, config.relativeBase()));
    }

    public Evaluation evaluate(String stageKey, String requirementPath, String requirementName,
            List<InputFile> all, boolean includeLikely) {
        return evaluate(stageKey, requirementPath, requirementName, all, includeLikely, Map.of());
    }

    public Evaluation evaluate(String stageKey, String requirementPath, String requirementName,
            List<InputFile> all, boolean includeLikely, Map<String,String> associationOverrides) {
        return evaluate(stageKey, requirementPath, requirementName, all, includeLikely, associationOverrides, null);
    }

    public Evaluation evaluate(String stageKey, String requirementPath, String requirementName,
            List<InputFile> all, boolean includeLikely, Map<String,String> associationOverrides, StageRuleConfig config) {
        if (!enabled(stageKey) || config != null && (!stageKey.equals(config.stageKey()) || !config.enabled())) return new Evaluation(false, "ASDM_STAGE_DISABLED", List.of(), List.of());
        String req = normalizedRequirement(requirementPath);
        List<ClassifiedFile> relevant = new ArrayList<>();
        for (InputFile file : all) {
            ClassifiedFile classified = classify(stageKey, req, requirementName, file, includeLikely, config);
            if (classified != null && associationOverrides != null) {
                String override = associationOverrides.get(file.path());
                if ("CONFIRMED".equals(override)) classified = new ClassifiedFile(classified.path(), classified.blobSha(), classified.size(),
                        classified.content(), "CONFIRMED", Math.max(90, classified.score()), "MANUAL_CONFIRMED", classified.deliveryMode(), true);
                else if ("EXCLUDED".equals(override)) classified = new ClassifiedFile(classified.path(), classified.blobSha(), classified.size(),
                        classified.content(), "EXCLUDED", classified.score(), "MANUAL_EXCLUDED", classified.deliveryMode(), false);
            }
            if (classified != null) relevant.add(classified);
        }
        relevant.sort(Comparator.comparing(ClassifiedFile::path));
        List<ClassifiedFile> selected = relevant.stream().filter(ClassifiedFile::selected).toList();
        List<String> markers = new ArrayList<>();
        boolean ready = switch (stageKey) {
            case "REQUIREMENT" -> hasMarkerOr(selected, req + "/requirement-doc.md", req, config, 0, markers);
            case "PRODUCT" -> hasMarkerOr(selected, req + "/system-function-list.md", req, config, 0, markers)
                    & any(selected, f -> (hasMarkerGroup(config,1) || basename(f.path()).matches("SYS-[^/]+\\.md"))
                    && substantive(f.content()) && markerPath(config,1,f.path(),req), markers);
            case "ARCHITECTURE_DESIGN" -> any(selected, f -> markerPath(config,0,f.path(),req) && (hasMarkerGroup(config,0) || basename(f.path()).equals("system-design.md"))
                    && substantive(f.content()), markers)
                    & any(selected, f -> markerPath(config,1,f.path(),req) && (hasMarkerGroup(config,1) || Set.of("task-list.md", "task-list-frontend.md").contains(basename(f.path())))
                    && substantive(f.content()), markers)
                    & any(selected, f -> markerPath(config,2,f.path(),req) && (hasMarkerGroup(config,2) || f.path().contains("/taskList/") && basename(f.path()).matches("req-[^/]+\\.md"))
                    && substantive(f.content()), markers);
            case "UI_DESIGN" -> hasMarkerOr(selected, req + "/prototypes/modification-summary.md", req, config, 0, markers);
            case "FRONTEND_CODING" -> tasksReady(selected, true, markers, config, req);
            case "BACKEND_CODING" -> tasksReady(selected, false, markers, config, req);
            case "TESTING" -> testPair(selected, requirementName, markers, config, req);
            default -> false;
        };
        return new Evaluation(ready, ready ? "READY" : "ASDM_MARKER_NOT_READY", List.copyOf(markers), List.copyOf(relevant));
    }

    private ClassifiedFile classify(String stage, String req, String name, InputFile file, boolean includeLikely, StageRuleConfig config) {
        String path = file.path();
        if (path == null || !path.startsWith("workspace/") || path.length() > 1600 || path.contains("..")
                || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gitignore") || basename(lower).startsWith("readme")
                || basename(lower).startsWith(".") || Set.of("features-list.md", "completion_report.md").contains(basename(lower))) return null;
        if (wrongCodingStage(stage, lower) || codingTaskOutsideCodingStage(stage, lower)) return null;
        for (String segment : lower.split("/")) if ((config==null?EXCLUDED_SEGMENTS:SECURITY_EXCLUDED_SEGMENTS).contains(segment)) return null;
        if (config != null && config.excludeGlobs().stream().anyMatch(g -> globMatches(g, path, req, config.relativeBase()))) return null;
        if (config == null ? !stagePattern(stage, lower, req.toLowerCase(Locale.ROOT))
                : config.includeGlobs().stream().noneMatch(g -> globMatches(g, path, req, config.relativeBase()))) return null;
        boolean exact = path.startsWith(req + "/");
        boolean testName = stage.equals("TESTING") && normalizedName(name).length() >= 3
                && normalizedName(path).contains(normalizedName(name));
        int score = exact ? 100 : testName ? 90 : 0;
        if (!exact && path.matches("workspace/REQ-[A-Za-z0-9_-]+/.*")
                && req.substring(req.lastIndexOf('/') + 1).equals(path.split("/")[1])) score = 90;
        if (!exact && score == 0 && file.changedSinceBaseline()) score = 25;
        boolean explicitId = file.content() != null
                && file.content().contains(req.substring(req.lastIndexOf('/') + 1));
        if (!exact && score > 0 && (path.startsWith("workspace/testwork/")
                || path.startsWith("workspace/code-dev/") || path.startsWith("workspace/project-specification/")
                || path.startsWith("workspace/features/"))) score += 35;
        if (!exact && score > 0 && explicitId) score += 35;
        if (!exact && score > 0 && normalizedName(name).length() >= 3
                && normalizedName(path).contains(normalizedName(name))) score += 15;
        boolean shared = path.startsWith("workspace/reviews/") || path.startsWith("workspace/features/")
                || path.startsWith("workspace/testwork/") && !testName;
        String association = shared && !explicitId && !testName ? "UNCERTAIN" : score >= 90 ? "CONFIRMED"
                : score >= 65 ? "LIKELY" : "UNCERTAIN";
        String reason = exact ? "BOUND_REQUIREMENT_DIRECTORY" : testName ? "REQUIREMENT_NAME_TEST_DIRECTORY"
                : shared ? "SHARED_WORKSPACE_REQUIRES_REVIEW" : score >= 65 ? "PATH_AND_CONTENT_MATCH" : "WEAK_PATH_MATCH";
        String delivery = lower.endsWith(".md") ? "DOCX" : "DRIVE_FILE";
        boolean selected = "CONFIRMED".equals(association) || "LIKELY".equals(association) && includeLikely;
        return new ClassifiedFile(path, file.blobSha(), file.size(), file.content(), association, score, reason,
                delivery, selected);
    }

    private boolean globMatches(String glob, String path, String req, String base) {
        if (glob == null || glob.isBlank()) return false;
        String reqDir = req == null || req.isBlank() ? "" : req.substring(req.lastIndexOf('/') + 1);
        String reqId = reqDir.matches("REQ-[A-Za-z0-9]+(?:[-_].*)?") ? reqDir.replaceFirst("^((?:REQ-[A-Za-z0-9]+)).*$", "$1") : "";
        String reqName = reqId.isBlank() ? "" : reqDir.substring(reqId.length()).replaceFirst("^[-_]+", "").replaceAll("[^\\p{IsHan}A-Za-z0-9_-]", "");
        String expanded = glob.replace("${REQ_PATH}", req == null ? "" : req)
                .replace("${REQ_DIR}", reqDir).replace("${REQ_ID}", reqId).replace("${REQ_NAME}", reqName);
        if (base != null && !base.isBlank() && !expanded.startsWith("workspace/") && !expanded.startsWith("${REQ_")) expanded = base.replaceAll("/+$", "") + "/" + expanded;
        StringBuilder regex = new StringBuilder("^");
        for (int i=0;i<expanded.length();i++) {
            char c=expanded.charAt(i);
            if (c=='*') {
                if (i+1<expanded.length() && expanded.charAt(i+1)=='*') {
                    if (i+2<expanded.length() && expanded.charAt(i+2)=='/') { regex.append("(?:.*/)?"); i+=2; }
                    else { regex.append(".*"); i++; }
                }
                else regex.append("[^/]*");
            } else if (c=='?') regex.append("[^/]");
            else if ("\\.[]{}()+-^$|".indexOf(c)>=0) regex.append('\\').append(c);
            else regex.append(c);
        }
        regex.append('$');
        try { return path.matches(regex.toString()); } catch (PatternSyntaxException ex) { return false; }
    }

    private boolean wrongCodingStage(String stage, String path) {
        boolean frontend = path.contains("/frontend/") || path.contains("/web/")
                || basename(path).startsWith("frontend-") || basename(path).startsWith("web-");
        boolean backend = path.contains("/backend/") || basename(path).startsWith("backend-");
        return "FRONTEND_CODING".equals(stage) && !frontend
                || "BACKEND_CODING".equals(stage) && frontend && !backend;
    }

    private boolean codingTaskOutsideCodingStage(String stage, String path) {
        return "ARCHITECTURE_DESIGN".equals(stage)
                && (path.contains("/frontend/") || path.contains("/backend/") || path.contains("/web/"))
                && (basename(path).equals("task-list.md") || basename(path).equals("task-list-frontend.md"));
    }

    private boolean stagePattern(String stage, String path, String req) {
        String base = basename(path);
        return switch (stage) {
            case "REQUIREMENT" -> path.startsWith(req + "/") && Set.of("requirement-doc.md",
                    "clarification-notes.md", "change-log.md", "workflow-state.md").contains(base);
            case "PRODUCT" -> path.startsWith(req + "/") && (Set.of("feature-list.md", "system-function-list.md",
                    "system-overview.md").contains(base) || path.startsWith(req + "/systems/") && base.endsWith(".md"));
            case "ARCHITECTURE_DESIGN" -> base.matches("(?i)(system-design|impl-context|impl-reference|db-changes|task-list(?:-frontend)?|api-[^/]+|algorithm-[^/]+|req-[^/]+|[^/]+-spec)\\.md")
                    || path.contains("/database/") && base.endsWith(".sql");
            case "UI_DESIGN" -> path.startsWith(req + "/prototypes/") || path.startsWith(req + "/prototype/");
            case "FRONTEND_CODING" -> (base.equals("task-list.md") || base.endsWith("-review.md"))
                    && (path.contains("frontend") || path.contains("web") || path.startsWith(req + "/"));
            case "BACKEND_CODING" -> (base.equals("task-list.md") || base.endsWith("-execution-log.md")
                    || base.endsWith("-review.md")) && !path.contains("frontend") && !path.contains("web");
            case "TESTING" -> path.startsWith("workspace/testwork/") && (base.endsWith("-测试用例.xlsx")
                    || base.endsWith("-测试用例评审报告.md") || base.equals("req-structure.md")
                    || base.endsWith("-思维脑图.md"));
            default -> false;
        };
    }

    private boolean tasksReady(List<ClassifiedFile> files, boolean frontend, List<String> markers, StageRuleConfig config, String req) {
        List<ClassifiedFile> lists = files.stream().filter(f -> f.selected()
                && (hasMarkerGroup(config,0) || basename(f.path()).equals("task-list.md"))
                && markerPath(config,0,f.path(),req)
                && (hasMarkerGroup(config,0) || (frontend ? f.path().contains("frontend") || f.path().contains("web") : !f.path().contains("frontend") && !f.path().contains("web")))).toList();
        if (lists.isEmpty()) return false;
        Map<String, String> statuses = new HashMap<>();
        for (ClassifiedFile list : lists) {
            Map<String, String> parsed = parseTaskStates(list.content());
            if (parsed.isEmpty()) return false;
            statuses.putAll(parsed);
            markers.add(list.path());
        }
        if (statuses.isEmpty() || statuses.values().stream().anyMatch(s -> !Set.of("DONE", "COMPLETED").contains(s))) return false;
        if (frontend) return true;
        Set<String> successful = new HashSet<>();
        for (ClassifiedFile file : files) {
            if (!file.selected() || (!hasMarkerGroup(config,1) && !file.path().endsWith("-execution-log.md")) || !markerPath(config,1,file.path(),req) || file.content() == null) continue;
            String text = file.content().toUpperCase(Locale.ROOT);
            if (text.matches("(?s).*\\b(ERROR|BLOCKED|FAILED|失败|阻塞)\\b.*")
                    || !text.matches("(?s).*\\b(DONE|SUCCESS|COMPLETED|成功)\\b.*")) continue;
            for (String id : statuses.keySet()) if (file.path().contains(id) || text.contains(id.toUpperCase(Locale.ROOT))) successful.add(id);
            markers.add(file.path());
        }
        return successful.containsAll(statuses.keySet());
    }

    public Map<String, String> parseTaskStates(String markdown) {
        if (markdown == null || markdown.isBlank()) return Map.of();
        Map<String, String> states = new HashMap<>();
        Matcher rows = TASK_ROW.matcher(markdown);
        while (rows.find()) {
            String id = rows.group(1).trim();
            if (id.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,80}")) states.put(id, rows.group(2).toUpperCase(Locale.ROOT));
        }
        Matcher kv = TASK_KV.matcher(markdown);
        while (kv.find()) states.put(kv.group(1), kv.group(2).toUpperCase(Locale.ROOT));
        return Map.copyOf(states);
    }

    private boolean testPair(List<ClassifiedFile> files, String name, List<String> markers, StageRuleConfig config, String req) {
        String normalized = normalizedName(name);
        if (normalized.length() < 3) return false;
        for (ClassifiedFile file : files) {
            if (!file.selected() || (!hasMarkerGroup(config,0) && !file.path().endsWith("-测试用例.xlsx")) || !markerPath(config,0,file.path(),req)) continue;
            String dir = file.path().substring(0, file.path().lastIndexOf('/') + 1);
            if (!normalizedName(dir).contains(normalized)) continue;
            for (ClassifiedFile review : files) if (review.selected() && review.path().startsWith(dir)
                    && (hasMarkerGroup(config,1) || review.path().endsWith("-测试用例评审报告.md")) && substantive(review.content()) && markerPath(config,1,review.path(),req)) {
                markers.add(file.path()); markers.add(review.path()); return true;
            }
        }
        return false;
    }

    private boolean has(List<ClassifiedFile> files, String path, List<String> markers) {
        return any(files, f -> f.path().equals(path) && substantive(f.content()), markers);
    }
    private boolean hasMarkerOr(List<ClassifiedFile> files, String fallback, String req, StageRuleConfig config, int group, List<String> markers) {
        return any(files, f -> markerPath(config,group,f.path(),req) && (config != null && group < config.markerGroups().size() || f.path().equals(fallback)) && substantive(f.content()), markers);
    }
    boolean markerPath(StageRuleConfig config, int group, String path, String req) {
        if (config == null || config.markerGroups().isEmpty()) return true;
        if (group >= config.markerGroups().size()) return false;
        return config.markerGroups().get(group).stream().anyMatch(glob -> globMatches(glob,path,req,config.relativeBase()));
    }
    private boolean hasMarkerGroup(StageRuleConfig config,int group) { return config != null && group < config.markerGroups().size() && !config.markerGroups().get(group).isEmpty(); }
    private boolean any(List<ClassifiedFile> files, java.util.function.Predicate<ClassifiedFile> match, List<String> markers) {
        return files.stream().filter(match).findFirst().map(f -> { markers.add(f.path()); return true; }).orElse(false);
    }
    private boolean substantive(String value) { return value != null && value.replaceAll("(?m)^\\s*(?:#.*|<!--.*-->|[-*]\\s*)$", "").trim().length() >= 12; }
    private String normalizedRequirement(String path) { return path == null ? "" : path.replaceAll("/+$", ""); }
    private String normalizedName(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", ""); }
    private String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    public record InputFile(String path, String blobSha, long size, String content, boolean changedSinceBaseline) {}
    public record ClassifiedFile(String path, String blobSha, long size, String content, String association,
            int score, String reason, String deliveryMode, boolean selected) {}
    public record Evaluation(boolean triggerReady, String diagnostic, List<String> markerEvidence,
            List<ClassifiedFile> files) {}
}
