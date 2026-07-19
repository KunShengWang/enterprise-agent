package com.agent.platform.ordercare.incident.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewerAssessmentDraftParserTests {

    private final ReviewerAssessmentDraftParser parser =
            new ReviewerAssessmentDraftParser(new ObjectMapper());

    @Test
    void parsesStrictReviewerSchema() {
        var draft = parser.parse("""
                {"schemaVersion":"reviewer-assessment-v1","confirmedFacts":[
                  {"evidenceSubtype":"DEAD_LETTER_SET","statement":"死信事实已确认","evidenceIds":["ev-dlq"]}
                ],"rootCauseCandidates":[],"recommendations":[],"clarificationRequest":null,"acknowledgedConflictIds":[]}
                """);

        assertEquals("reviewer-assessment-v1", draft.schemaVersion());
        assertEquals(1, draft.confirmedFacts().size());
        assertEquals("ev-dlq", draft.confirmedFacts().get(0).evidenceIds().get(0));
    }

    @Test
    void unwrapsKnownSchemaWrapperWhenInnerSchemaIsStrict() {
        var draft = parser.parse("""
                ```json
                {"reviewer-assessment-v1":{
                  "schemaVersion":"reviewer-assessment-v1",
                  "confirmedFacts":[{"evidenceSubtype":"ORDER_STATUS_SET","statement":"订单事实已确认","evidenceIds":["ev-order"]}],
                  "rootCauseCandidates":[],"recommendations":[],"acknowledgedConflictIds":[]
                }}
                ```
                """);

        assertEquals("reviewer-assessment-v1", draft.schemaVersion());
        assertEquals(1, draft.confirmedFacts().size());
    }

    @Test
    void singularModelShapeRemainsInvalidSoOrchestratorUsesDeterministicFallback() {
        var draft = parser.parse("""
                {"reviewer-assessment-v1":{
                  "schemaVersion":"reviewer-assessment-v1",
                  "confirmedFact":{"summary":"三笔订单事实","evidenceIds":["ev-order"]},
                  "rootCause":{"summary":"消息未消费","evidenceIds":["ev-dlq"]},
                  "recommendation":{"summary":"建议核对消费者","evidenceIds":["ev-dlq"]}
                }}
                """);

        assertEquals("reviewer-assessment-v1", draft.schemaVersion());
        assertTrue(draft.confirmedFacts().isEmpty());
        assertTrue(draft.rootCauseCandidates().isEmpty());
        assertTrue(draft.recommendations().isEmpty());
    }
}
