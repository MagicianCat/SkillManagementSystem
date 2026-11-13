package com.company.skillplatform.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsdmCommandSupportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }

    private static void assertCode(ThrowingRunnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test void canonicalJsonIgnoresWhitespaceAndKeepsArrayOrder() throws Exception {
        String first = AsdmCommandSupport.canonicalJson(mapper,
                json(" [ {\"toolName\":\" code-dev-sc274 \",\"firstCategoryCode\":\"backend\",\"extra\":1}, {\"toolName\":\"api-test-gen\",\"firstCategoryCode\":\"testing\"} ] "));
        String second = AsdmCommandSupport.canonicalJson(mapper,
                json("[{\"toolName\":\" code-dev-sc274 \",\"firstCategoryCode\":\"backend\",\"extra\":1},{\"toolName\":\"api-test-gen\",\"firstCategoryCode\":\"testing\"}]"));
        assertThat(first).isEqualTo(second);
        assertThat(AsdmCommandSupport.sha256Hex(first)).isEqualTo(AsdmCommandSupport.sha256Hex(second));
    }

    @Test void parsesToolsetCategoriesAndTrimsNames() throws Exception {
        Map<String, String> stages = Map.of("backend", "BACKEND_CODING", "other", "OTHER");
        var toolsets = AsdmCommandSupport.parse(json("""
                [{"toolName":" code-dev-sc274 ","firstCategoryCode":"backend"},
                 {"toolName":"代码开发工具集","firstCategoryCode":" other "}]
                """), stages::get);
        assertThat(toolsets).extracting(AsdmCommandSupport.ToolsetCategory::toolsetName)
                .containsExactly("code-dev-sc274", "代码开发工具集");
        assertThat(toolsets).extracting(AsdmCommandSupport.ToolsetCategory::firstCategoryCode)
                .containsExactly("backend", "other");
        assertThat(toolsets).extracting(AsdmCommandSupport.ToolsetCategory::developmentStage)
                .containsExactly("BACKEND_CODING", "OTHER");
    }

    @Test void receiveValidationRejectsExactAndWhitespaceDuplicates() throws Exception {
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("""
                [{"toolName":"code-dev-sc274","firstCategoryCode":"backend"},
                 {"toolName":"code-dev-sc274","firstCategoryCode":"testing"}]
                """)), "ASDM_TOOLSET_DUPLICATE");
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("""
                [{"toolName":"code-dev-sc274","firstCategoryCode":"backend"},
                 {"toolName":"  code-dev-sc274  ","firstCategoryCode":"testing"}]
                """)), "ASDM_TOOLSET_DUPLICATE");
    }

    @Test void receiveValidationTreatsCaseAndUnicodeNormalizationAsDuplicate() throws Exception {
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("""
                [{"toolName":"Code-Dev-SC274","firstCategoryCode":"backend"},
                 {"toolName":"code-dev-sc274","firstCategoryCode":"testing"}]
                """)), "ASDM_TOOLSET_DUPLICATE");
        // "é" 的组合字符与预组合字符必须视为同一工具集。
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("""
                [{"toolName":"caf\\u00e9-tool","firstCategoryCode":"other"},
                 {"toolName":"cafe\\u0301-tool","firstCategoryCode":"backend"}]
                """)), "ASDM_TOOLSET_DUPLICATE");
    }

    @Test void receiveValidationRejectsPathLikeEmptyOrOverlongNames() throws Exception {
        for (String bad : new String[]{"../tools", "foo/bar", "foo\\\\bar", ".", "..", "", "   "}) {
            assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json(
                    "[{\"toolName\":\"" + bad.replace("\\", "\\\\") + "\",\"firstCategoryCode\":\"other\"}]")),
                    "ASDM_TOOLSET_NAME_INVALID");
        }
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json(
                "[{\"toolName\":\"" + "a".repeat(129) + "\",\"firstCategoryCode\":\"other\"}]")),
                "ASDM_TOOLSET_NAME_INVALID");
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json(
                "[{\"toolName\":\"bad\\u0001name\",\"firstCategoryCode\":\"other\"}]")),
                "ASDM_TOOLSET_NAME_INVALID");
    }

    @Test void receiveValidationRejectsMalformedMetadata() throws Exception {
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("{\"toolName\":\"code-dev\"}")),
                "ASDM_TOOLSET_METADATA_INVALID");
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("[{\"toolName\":123}]")),
                "ASDM_TOOLSET_NAME_INVALID");
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json("[\"code-dev\"]")),
                "ASDM_TOOLSET_METADATA_INVALID");
        assertCode(() -> AsdmCommandSupport.validateIncomingToolsets(json(
                "[{\"toolName\":\"code-dev\",\"firstCategoryCode\":123}]")),
                "ASDM_TOOLSET_METADATA_INVALID");
    }

    @Test void receiveValidationAcceptsChineseEmptyAndMissingArrays() throws Exception {
        AsdmCommandSupport.validateIncomingToolsets(json("[{\"toolName\":\"中文工具集\",\"firstCategoryCode\":\"other\"}]"));
        AsdmCommandSupport.validateIncomingToolsets(json("[]"));
        AsdmCommandSupport.validateIncomingToolsets(null);
        assertThat(AsdmCommandSupport.parse(json("[]"), code -> "OTHER")).isEmpty();
        assertThat(AsdmCommandSupport.parse(null, code -> "OTHER")).isEmpty();
    }

    @Test void importRejectsInvalidOrUnknownFirstCategory() throws Exception {
        assertCode(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"code-dev-sc274\",\"firstCategoryCode\":\"BACKEND_CODING\"}]"), code -> null),
                "ASDM_FIRST_CATEGORY_INVALID");
        assertCode(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"code-dev-sc274\"}]"), code -> "BACKEND_CODING"),
                "ASDM_FIRST_CATEGORY_INVALID");
    }
}