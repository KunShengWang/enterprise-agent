package com.agent.platform.memory;

import com.agent.platform.guardrail.RegexSensitiveDataFilter;
import com.agent.platform.llm.LlmService;
import com.agent.platform.prompt.PromptRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
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
                        + "\"confidence\":0.99}],\"profileItems\":[]}"));

        assertTrue(extractor.extract("conversation-a", "user-a",
                userMessage("这次预算 60 万，三周内交付，不要 Supplier A")).longTermMemories().isEmpty());
        assertTrue(extractor.extract("conversation-a", "user-a",
                userMessage("本次选择 Supplier D")).longTermMemories().isEmpty());
        verifyNoInteractions(llm);
    }

    @Test
    void durablePreferenceAcceptsAnExactSourceSpanAndUsesTheSimplifiedProtocol() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"交付速度\",\"confidence\":0.91}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后采购研发工作站时，我通常更看重交付速度。"));

        assertEquals(1, extraction.longTermMemories().size());
        assertEquals(DurableMemoryType.PREFERENCE, extraction.longTermMemories().get(0).type());
        assertEquals("交付速度", extraction.longTermMemories().get(0).content());
        ArgumentCaptor<PromptRequest> prompt = ArgumentCaptor.forClass(PromptRequest.class);
        verify(llm).complete(prompt.capture());
        assertFalse(prompt.getValue().systemPrompt().contains("durableIntent"));
        assertFalse(prompt.getValue().systemPrompt().contains("ephemeral"));
    }

    @Test
    void stableInstructionAndOnlyAllowedAutomaticProfileKeysAreAccepted() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"STABLE_INSTRUCTION\","
                        + "\"content\":\"以后请记住我的偏好：回复先给结论再解释\",\"confidence\":0.88}],"
                        + "\"profileItems\":["
                        + "{\"key\":\"language\",\"value\":\"默认使用中文且回复简洁\",\"confidence\":0.9},"
                        + "{\"key\":\"response_style\",\"value\":\"以后请记住我的偏好：回复先给结论再解释\",\"confidence\":0.9},"
                        + "{\"key\":\"budget\",\"value\":\"600000\",\"confidence\":0.99}]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后请记住我的偏好：回复先给结论再解释，默认使用中文且回复简洁。"));

        assertEquals(List.of(DurableMemoryType.STABLE_INSTRUCTION), extraction.longTermMemories().stream()
                .map(LongTermMemoryDraft::type).toList());
        assertEquals(List.of("language", "response_style"), extraction.profileItems().stream()
                .map(UserProfileItem::key).toList());
        assertEquals(List.of("默认使用中文且回复简洁", "以后请记住我的偏好：回复先给结论再解释"),
                extraction.profileItems().stream().map(UserProfileItem::value).toList());
        assertTrue(extraction.profileItems().stream().noneMatch(item -> item.key().equals("budget")));
        assertEquals("llm-message:conversation-a;createdAt=2026-01-01T00:00:00Z",
                extraction.profileItems().get(0).source());
    }

    @Test
    void hallucinatedMemoryContentIsRejectedEvenWhenTypeAndConfidenceAreValid() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"我喜欢非常简短的回答\",\"confidence\":0.95}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后默认使用中文回答。"));

        assertTrue(extraction.longTermMemories().isEmpty());
    }

    @Test
    void paraphrasedMemoryContentIsRejectedBecauseItIsNotAnExactSubstring() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[{\"type\":\"PREFERENCE\","
                        + "\"content\":\"采购时优先考虑交货速度\",\"confidence\":0.95}],\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后采购时我通常更看重交付速度。"));

        assertTrue(extraction.longTermMemories().isEmpty());
    }

    @Test
    void hallucinatedAutomaticProfileValueIsRejected() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[],\"profileItems\":["
                        + "{\"key\":\"language\",\"value\":\"English\",\"confidence\":0.95}]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后默认使用中文回答。"));

        assertTrue(extraction.profileItems().isEmpty());
    }

    @Test
    void automaticProfileValueAcceptsAnExactSourcePhrase() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[],\"profileItems\":["
                        + "{\"key\":\"language\",\"value\":\"中文\",\"confidence\":0.95}]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后默认使用中文回答。"));

        assertEquals(1, extraction.profileItems().size());
        assertEquals("中文", extraction.profileItems().get(0).value());
    }

    @Test
    void legacyUnknownAndMalformedTypesAreRejected() {
        for (String item : List.of(
                "{\"category\":\"business_fact\",\"content\":\"事实\",\"confidence\":0.9}",
                "{\"category\":\"decision\",\"content\":\"决策\",\"confidence\":0.9}",
                "{\"category\":\"open_task\",\"content\":\"待办\",\"confidence\":0.9}",
                "{\"category\":\"identity\",\"content\":\"身份\",\"confidence\":0.9}",
                "{\"type\":\"preference\",\"content\":\"旧偏好\",\"confidence\":0.9}",
                "{\"type\":\"instruction\",\"content\":\"旧指令\",\"confidence\":0.9}",
                "{\"type\":\"BUSINESS_FACT\",\"content\":\"事实\",\"confidence\":0.9}",
                "{\"type\":\"UNKNOWN\",\"content\":\"未知\",\"confidence\":0.9}"
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
                            + "\"content\":\"以后采购通常交付优先\",\"confidence\":" + confidence
                            + "}],\"profileItems\":[]}"));
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
                        + "\"content\":\"请记住我的手机号 13800138000\",\"confidence\":0.95}],"
                        + "\"profileItems\":[]}"));

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
                        + "\"content\":\"以后采购时 Supplier D 报价 58 万\",\"confidence\":0.95}],"
                        + "\"profileItems\":[]}"));

        MemoryExtraction extraction = extractor.extract("conversation-a", "user-a",
                userMessage("以后采购时 Supplier D 报价 58 万"));
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

    @Test
    void unstableUserIdsDoNotInvokeTheLlm() {
        LlmService llm = mock(LlmService.class);
        LlmMemoryExtractor extractor = extractor(llm, response(
                "{\"longTermMemories\":[],\"profileItems\":[]}"));

        for (String userId : Arrays.asList(null, "", "  ", "anonymous", "anonymous-user", "ANONYMOUS")) {
            assertTrue(extractor.extract("conversation-a", userId,
                    userMessage("以后默认使用中文回答。" )).longTermMemories().isEmpty());
        }

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
