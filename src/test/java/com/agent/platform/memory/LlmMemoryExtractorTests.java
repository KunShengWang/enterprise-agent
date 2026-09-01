package com.agent.platform.memory;

import com.agent.platform.guardrail.RegexSensitiveDataFilter;
import com.agent.platform.llm.LlmService;
import com.agent.platform.prompt.PromptRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmMemoryExtractorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ephemeralProcurementFactsAreRejectedBeforeCallingTheLlm() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\",\"content\":\"预算 60 万\","
                        + "\"confidence\":0.99,\"durableIntent\":true,\"ephemeral\":false}],"
                        + "\"profileItems\":[]}"));

        assertTrue(extractor.extract("conversation-a", "user-a",
                userMessage("这次预算 60 万，三周内交付，不要 Supplier A")).longTermMemories().isEmpty());
        assertTrue(extractor.extract("conversation-a", "user-a",
                userMessage("本次选择 Supplier D")).longTermMemories().isEmpty());
        verifyNoInteractions(llm);
    }

    @Test
    void durablePreferenceIsAcceptedWithTypedProtocol() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"采购研发工作站时更看重交付速度\",\"confidence\":0.91,"
                        + "\"durableIntent\":true,\"ephemeral\":false}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后采购研发工作站时，我通常更看重交付速度。"));

        assertEquals(1, extraction.longTermMemories().size());
        assertEquals(DurableMemoryType.PREFERENCE, extraction.longTermMemories().get(0).type());
        assertEquals("采购研发工作站时更看重交付速度", extraction.longTermMemories().get(0).content());
        verify(llm).complete(any(PromptRequest.class));
    }

    @Test
    void stableInstructionAndOnlyAllowedAutomaticProfileKeysAreAccepted() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"STABLE_INSTRUCTION\","
                        + "\"content\":\"回复先给结论再解释\",\"confidence\":0.88,"
                        + "\"durableIntent\":true,\"ephemeral\":false}],"
                        + "\"profileItems\":["
                        + "{\"key\":\"language\",\"value\":\"中文\",\"confidence\":0.9,"
                        + "\"durableIntent\":true,\"ephemeral\":false},"
                        + "{\"key\":\"response_style\",\"value\":\"简洁\",\"confidence\":0.9,"
                        + "\"durableIntent\":true,\"ephemeral\":false},"
                        + "{\"key\":\"budget\",\"value\":\"600000\",\"confidence\":0.99,"
                        + "\"durableIntent\":true,\"ephemeral\":false}]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后请记住我的偏好：回复先给结论再解释，默认使用中文且简洁。"));

        assertEquals(List.of(DurableMemoryType.STABLE_INSTRUCTION), extraction.longTermMemories().stream()
                .map(LongTermMemoryDraft::type).toList());
        assertEquals(List.of("language", "response_style"), extraction.profileItems().stream()
                .map(UserProfileItem::key).toList());
        assertTrue(extraction.profileItems().stream().noneMatch(item -> item.key().equals("budget")));
    }

    @Test
    void legacyUnknownAndMalformedTypesAreRejected() {
        for (String item : List.of(
                "{\"category\":\"business_fact\",\"content\":\"事实\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"category\":\"decision\",\"content\":\"决策\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"category\":\"open_task\",\"content\":\"待办\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"category\":\"identity\",\"content\":\"身份\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"type\":\"preference\",\"content\":\"旧偏好\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"type\":\"instruction\",\"content\":\"旧指令\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"type\":\"BUSINESS_FACT\",\"content\":\"事实\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"type\":\"UNKNOWN\",\"content\":\"未知\",\"confidence\":0.9,\"durableIntent\":true,\"ephemeral\":false}",
                "{\"type\":\"PREFERENCE\",\"content\":\"缺少标记\",\"confidence\":0.9}",
                "{\"type\":\"PREFERENCE\",\"content\":\"错误标记\",\"confidence\":0.9,\"durableIntent\":\"true\",\"ephemeral\":false}"
        )) {
            LlmService llm = mock(LlmService.class);
            LlmMemoryExtractor extractor = extractor(llm, response(
                    "{\"longTermMemories\":[" + item + "],\"profileItems\":[]}"));
            assertTrue(extractor.extract("conversation-a", "user-a",
                    userMessage("以后采购通常交付优先。" )).longTermMemories().isEmpty(), item);
        }
    }

    @Test
    void invalidConfidenceIsRejectedWithoutClamping() {
        for (String confidence : List.of("1.2", "-0.1", "NaN", "Infinity", "-Infinity")) {
            LlmService llm = mock(LlmService.class);
            LlmMemoryExtractor extractor = extractor(llm, response(
                    "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                            + "\"content\":\"交付优先\",\"confidence\":" + confidence
                            + ",\"durableIntent\":true,\"ephemeral\":false}],\"profileItems\":[]}"));
            assertTrue(extractor.extract("conversation-a", "user-a",
                    userMessage("以后采购通常交付优先。" )).longTermMemories().isEmpty(), confidence);
        }
    }

    @Test
    void sensitiveCandidateIsDiscardedInsteadOfPersistingAPlaceholder() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(
                llm, objectMapper, new RegexSensitiveDataFilter());
        when(llm.complete(any(PromptRequest.class))).thenReturn(response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"请记住我的手机号 13800138000\",\"confidence\":0.95,"
                        + "\"durableIntent\":true,\"ephemeral\":false}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("请记住我的手机号 13800138000"));

        assertTrue(extraction.longTermMemories().isEmpty());
        assertFalse(extraction.profileItems().stream().anyMatch(item -> item.value().contains("REDACTED")));
    }

    @Test
    void dynamicProcurementFactIsRejectedEvenWhenTheLlmLabelsItAsAPreference() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"Supplier D 当前报价 58 万\",\"confidence\":0.95,"
                        + "\"durableIntent\":true,\"ephemeral\":false}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后采购研发工作站时，我通常更看重交付速度。"));
        assertTrue(extraction.longTermMemories().isEmpty());
    }

    @Test
    void draftIsFailClosedForTypeContentAndConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(null, "内容", 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, " ", 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "内容", -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "内容", 1.1));
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "内容", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new LongTermMemoryDraft(DurableMemoryType.PREFERENCE, "内容", Double.POSITIVE_INFINITY));
    }

    @Test
    void nonDurableOrNonUserMessagesDoNotInvokeTheLlm() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[],\"profileItems\":[]}"));

        assertTrue(extractor.extract("conversation-a", "user-a",
                userMessage("交付优先。" )).longTermMemories().isEmpty());
        assertTrue(extractor.extract("conversation-a", "user-a",
                new MemoryMessage("assistant", "以后采购通常交付优先。", Instant.now()))
                .longTermMemories().isEmpty());
        verifyNoInteractions(llm);
    }

    private LlmMemoryExtractor extractor(LlmService llm, String response) {
        when(llm.complete(any(PromptRequest.class))).thenReturn(response);
        return new LlmMemoryExtractor(llm, objectMapper, value ->
                new com.agent.platform.guardrail.SensitiveDataFilterResult(value, List.of()));
    }

    private String response(String value) {
        return value;
    }

    private MemoryMessage userMessage(String content) {
        return new MemoryMessage("user", content, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
