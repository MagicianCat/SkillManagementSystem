package com.company.skillplatform.agentworkflow.application;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class RequirementTransitionPolicyTest {
 @Test void approvedCompletes(){var d=RequirementTransitionPolicy.apply("reviewer","SUCCESS","APPROVED",1,3);assertTrue(d.stageCompleted());assertFalse(d.humanRequired());}
 @Test void revisionLoopsToWriter(){var d=RequirementTransitionPolicy.apply("reviewer","SUCCESS","REVISION_REQUIRED",1,3);assertEquals("writer",d.nextNode());assertEquals(2,d.loopCount());}
 @Test void maxLoopRequiresHuman(){var d=RequirementTransitionPolicy.apply("reviewer","SUCCESS","REVISION_REQUIRED",3,3);assertTrue(d.humanRequired());assertEquals(4,d.loopCount());}
 @Test void failedRequiresHuman(){assertTrue(RequirementTransitionPolicy.apply("writer","FAILED","",0,3).humanRequired());}
}
