package com.company.skillplatform.skill.application;

import java.util.Locale;
import java.util.Map;

/** Deterministic baseline classifier for importing ECC skills into a required leaf category. */
public final class EccSkillCategoryClassifier {
    private EccSkillCategoryClassifier() {}

    private static final Map<String,String> EXACT = Map.ofEntries(
            Map.entry("api-design","architecture.integration"), Map.entry("architecture-decision-records","architecture.governance"),
            Map.entry("accessibility","ui.accessibility"), Map.entry("design-system","ui.visual"),
            Map.entry("e2e-testing","testing.web_e2e"), Map.entry("ai-regression-testing","testing.regression"),
            Map.entry("agent-eval","testing.agent_eval"), Map.entry("security-review","security.application"),
            Map.entry("security-scan","security.supply_chain"), Map.entry("deployment-patterns","deployment.cicd"),
            Map.entry("docker-patterns","deployment.docker"), Map.entry("kubernetes-patterns","deployment.kubernetes"),
            Map.entry("git-workflow","deployment.git"), Map.entry("market-research","requirement.market"),
            Map.entry("documentation-lookup","requirement.research"), Map.entry("plan-canvas","requirement.planning")
    );

    public static String classify(String skillKey) {
        String key=skillKey.toLowerCase(Locale.ROOT);
        String exact=EXACT.get(key); if(exact!=null)return exact;
        if(has(key,"test","tdd","verification","benchmark","eval","quality","coverage"))return testing(key);
        if(has(key,"security","compliance","hipaa","guard","vulnerability"))return "security.application";
        if(has(key,"deploy","docker","kubernetes","cicd","canary","runtime","uncloud","flox"))return deployment(key);
        if(has(key,"react","nextjs","vue","nuxt","angular","frontend","swift","ios","android","kotlin","flutter","compose"))return frontend(key);
        if(has(key,"springboot","java","jpa","python","django","fastapi","golang","rust","cpp","dotnet","laravel","perl","mysql","postgres","redis","clickhouse"))return backend(key);
        if(has(key,"architecture","api","mcp","agent","llm","network","latency","throughput","database"))return architecture(key);
        if(has(key,"design","ui","motion","brand","dashboard","slides","video","blender"))return "ui.visual";
        if(has(key,"research","competitive","discovery","blueprint","intent"))return "requirement.research";
        if(has(key,"product","roadmap","delivery","project"))return "product.roadmap";
        if(has(key,"content","seo","marketing","growth","article","social"))return "product.growth";
        if(has(key,"ops","billing","finance","logistics","inventory","email","messages"))return "product.operations";
        return "product.general";
    }
    private static String testing(String k){if(has(k,"e2e","browser","click-path"))return "testing.web_e2e";if(has(k,"benchmark","performance"))return "testing.performance";if(has(k,"agent","llm","eval"))return "testing.agent_eval";if(has(k,"regression"))return "testing.regression";if(has(k,"security"))return "testing.security";if(has(k,"coverage","verification","quality","gate"))return "testing.quality_gate";return "testing.unit";}
    private static String deployment(String k){if(has(k,"docker","container"))return "deployment.docker";if(has(k,"kubernetes","cloud"))return "deployment.kubernetes";if(has(k,"canary","rollback"))return "deployment.canary";if(has(k,"config","secret","environment","flox"))return "deployment.configuration";if(has(k,"runtime","installer"))return "deployment.runtime";return "deployment.cicd";}
    private static String frontend(String k){if(has(k,"react","nextjs"))return "frontend.react";if(has(k,"vue","nuxt"))return "frontend.vue";if(has(k,"angular"))return "frontend.angular";if(has(k,"swift","ios"))return "frontend.ios";if(has(k,"android","kotlin"))return "frontend.android";if(has(k,"flutter","compose","react-native"))return "frontend.cross_platform";return "frontend.engineering";}
    private static String backend(String k){if(has(k,"springboot","java","jpa","quarkus"))return "backend.jvm";if(has(k,"python","django","fastapi","pytorch"))return "backend.python";if(has(k,"node","nestjs","typescript"))return "backend.node";if(has(k,"golang"))return "backend.go";if(has(k,"rust","cpp"))return "backend.systems";if(has(k,"dotnet","csharp","fsharp"))return "backend.dotnet";if(has(k,"laravel","perl"))return "backend.php_perl";return "backend.database";}
    private static String architecture(String k){if(has(k,"mcp","api","connector"))return "architecture.integration";if(has(k,"agent","llm","recsys"))return "architecture.ai";if(has(k,"database","data","storage"))return "architecture.data";if(has(k,"network","cloud","distributed"))return "architecture.cloud";if(has(k,"latency","performance","throughput","reliability"))return "architecture.reliability";return "architecture.system";}
    private static boolean has(String value,String...tokens){for(String token:tokens)if(value.contains(token))return true;return false;}
}
