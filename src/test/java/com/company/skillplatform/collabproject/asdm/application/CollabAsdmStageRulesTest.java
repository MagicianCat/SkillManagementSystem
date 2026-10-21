package com.company.skillplatform.collabproject.asdm.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CollabAsdmStageRulesTest {
    private final CollabAsdmStageRules rules = new CollabAsdmStageRules();
    private static final String REQ = "workspace/requirements/REQ-001";

    private CollabAsdmStageRules.InputFile file(String path, String body) {
        return new CollabAsdmStageRules.InputFile(path,"0123456789abcdef0123456789abcdef01234567",body==null?100:body.length(),body,true);
    }

    @Test void requirementNeedsItsOwnDocumentAndKeepsProcessFiles() {
        var result=rules.evaluate("REQUIREMENT",REQ,"测试需求",List.of(
                file(REQ+"/clarification-notes.md","clarification was completed"),
                file(REQ+"/requirement-doc.md","requirement analysis is complete"),
                file("workspace/requirements/REQ-002/requirement-doc.md","another requirement is complete")),false);
        assertTrue(result.triggerReady());
        assertEquals(2,result.files().size());
        assertEquals(2,result.files().stream().filter(CollabAsdmStageRules.ClassifiedFile::selected).count());
    }

    @Test void productRequiresSystemListAndAtLeastOneSubstantiveSystemDocument() {
        var list=file(REQ+"/system-function-list.md","system function list for this requirement");
        var empty=file(REQ+"/systems/SYS-001.md","# Template\n");
        assertFalse(rules.evaluate("PRODUCT",REQ,"需求",List.of(list,empty),false).triggerReady());
        assertTrue(rules.evaluate("PRODUCT",REQ,"需求",List.of(list,
                file(REQ+"/systems/SYS-001.md","System one owns the billing features.")),false).triggerReady());
    }

    @Test void uiSummaryIsTheMarkerEvenWithoutHtml() {
        assertTrue(rules.evaluate("UI_DESIGN",REQ,"需求",List.of(
                file(REQ+"/prototypes/modification-summary.md","The prototype changed its order form.")),false).triggerReady());
    }

    @Test void frontendRequiresAllIdentifiedTasksDone() {
        String path=REQ+"/frontend/task-list.md";
        assertFalse(rules.evaluate("FRONTEND_CODING",REQ,"需求",List.of(file(path,
                "| FE-001 | Build page | DONE |\n| FE-002 | Review page | IN_PROGRESS |")),false).triggerReady());
        assertTrue(rules.evaluate("FRONTEND_CODING",REQ,"需求",List.of(file(path,
                "| FE-001 | Build page | DONE |\n| FE-002 | Review page | DONE |")),false).triggerReady());
    }

    @Test void backendRequiresSuccessfulLogForEveryDoneTask() {
        var list=file(REQ+"/backend/task-list.md","| BE-001 | Build API | DONE |\n| BE-002 | Add cache | DONE |");
        var first=file(REQ+"/backend/BE-001-execution-log.md","BE-001 final status SUCCESS");
        assertFalse(rules.evaluate("BACKEND_CODING",REQ,"需求",List.of(list,first),false).triggerReady());
        assertTrue(rules.evaluate("BACKEND_CODING",REQ,"需求",List.of(list,first,
                file(REQ+"/backend/BE-002-execution-log.md","BE-002 final status DONE")),false).triggerReady());
    }

    @Test void testingNeedsPairedFilesInMatchingRequirementDirectory() {
        String dir="workspace/testwork/测试需求/";
        assertFalse(rules.evaluate("TESTING",REQ,"测试需求",List.of(file(dir+"需求-测试用例.xlsx",null),
                file("workspace/testwork/另一个需求/需求-测试用例评审报告.md","This review covers different cases.")),false).triggerReady());
        assertTrue(rules.evaluate("TESTING",REQ,"测试需求",List.of(file(dir+"需求-测试用例.xlsx",null),
                file(dir+"需求-测试用例评审报告.md","This review covers the case design.")),false).triggerReady());
    }

    @Test void acceptanceAndToolingNeverTrigger() {
        var output=file("workspace/toolsets/requirement-doc.md","A tool template, not a delivery.");
        assertFalse(rules.evaluate("ACCEPTANCE",REQ,"需求",List.of(output),false).triggerReady());
        assertFalse(rules.evaluate("REQUIREMENT",REQ,"需求",List.of(output),false).triggerReady());
    }
}
