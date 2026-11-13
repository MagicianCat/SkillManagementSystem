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

    @Test void canonicalJsonIgnoresWhitespaceAndKeepsArrayOrder() throws Exception {
        String first = AsdmCommandSupport.canonicalJson(mapper,
                json(" [ {\"toolName\":\" b \",\"firstCategoryCode\":\"backend\",\"extra\":1}, {\"toolName\":\"a\",\"firstCategoryCode\":\"other\"} ] "));
        String second = AsdmCommandSupport.canonicalJson(mapper,
                json("[{\"toolName\":\" b \",\"firstCategoryCode\":\"backend\",\"extra\":1},{\"toolName\":\"a\",\"firstCategoryCode\":\"other\"}]"));
        assertThat(first).isEqualTo(second);
        assertThat(AsdmCommandSupport.sha256Hex(first)).isEqualTo(AsdmCommandSupport.sha256Hex(second));
    }

    @Test void parsesAndTrimsValidCommandMapping() throws Exception {
        Map<String, String> stages = Map.of("backend", "BACKEND_CODING", "other", "OTHER");
        var commands = AsdmCommandSupport.parse(json("""
                [{"toolName":" java-review-helper ","firstCategoryCode":"backend"},
                 {"toolName":"general-helper","firstCategoryCode":" other "}]
                """), stages::get);
        assertThat(commands).extracting(AsdmCommandSupport.Command::toolName).containsExactly("java-review-helper", "general-helper");
        assertThat(commands).extracting(AsdmCommandSupport.Command::firstCategoryCode).containsExactly("backend", "other");
        assertThat(commands).extracting(AsdmCommandSupport.Command::developmentStage).containsExactly("BACKEND_CODING", "OTHER");
    }

    @Test void rejectsInvalidCategoryAtImportTimeNotReceiveTime() throws Exception {
        assertThatThrownBy(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"helper\",\"firstCategoryCode\":\"BACKEND_CODING\"}]"), code -> null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ASDM_FIRST_CATEGORY_INVALID"));
    }

    @Test void rejectsDuplicateToolName() throws Exception {
        assertThatThrownBy(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"x\",\"firstCategoryCode\":\"backend\"},{\"toolName\":\"x\",\"firstCategoryCode\":\"testing\"}]"),
                code -> "BACKEND_CODING"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ASDM_COMMAND_DUPLICATE"));
    }

    @Test void rejectsEmptyOrControlToolName() throws Exception {
        assertThatThrownBy(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"  \",\"firstCategoryCode\":\"backend\"}]"), code -> "BACKEND_CODING"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ASDM_COMMAND_NAME_INVALID"));
        assertThatThrownBy(() -> AsdmCommandSupport.parse(
                json("[{\"toolName\":\"bad\\u0001name\",\"firstCategoryCode\":\"backend\"}]"), code -> "BACKEND_CODING"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ASDM_COMMAND_NAME_INVALID"));
    }

    @Test void rejectsNonArrayPayload() throws Exception {
        assertThatThrownBy(() -> AsdmCommandSupport.parse(json("{\"toolName\":\"x\"}"), code -> "OTHER"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ASDM_COMMAND_MAPPING_IMPORT_FAILED"));
        assertThat(AsdmCommandSupport.parse(json("[]"), code -> "OTHER")).isEmpty();
        assertThat(AsdmCommandSupport.parse(null, code -> "OTHER")).isEmpty();
    }
}