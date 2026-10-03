package com.agent.platform.procurement;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.*;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.runtime.*;
import com.agent.platform.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementV2EvalStoresTest {
    @Test void approvalDecisionIsConditionalAndExpiryCannotApprove() {
        var store=new ProcurementV2TrialDriver.EvalApprovalStore();
        var service=new LocalApprovalService(store,new AgentProperties());
        var r=new ApprovalRecord("a","r","s",new ToolCallRequest("procurement_create_rfq","t",Map.of()),"eval",ApprovalStatus.REQUESTED,"","",Instant.now().minusSeconds(10),Instant.now().minusSeconds(1),null);
        store.save(r);
        assertEquals(ApprovalStatus.EXPIRED,service.find("a").orElseThrow().status());
        assertThrows(IllegalArgumentException.class,()->service.decide("a",true,"human","approve"));
        assertFalse(store.decideIfRequestedAndNotExpired("a",r,Instant.now()));
    }
    @Test void onlyOneResumeClaimCanWin() {
        var store=new ProcurementV2TrialDriver.EvalRunStore();
        var r=AgentRunRecord.create("r","trace","session",new AgentRequest("session","user","task",Map.of()));
        store.create(r);assertTrue(store.claimForResume("r").isEmpty());
        store.update("r",v->v.waitingForApproval("a",new ToolCallRequest("procurement_create_rfq","t",Map.of()),List.of(),List.of(),false,null));
        assertTrue(store.claimForResume("r").isPresent());assertTrue(store.claimForResume("r").isEmpty());
        assertEquals(1,store.find("r").orElseThrow().resumeCount());
    }
}
