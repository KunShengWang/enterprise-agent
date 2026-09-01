package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.memory.ConversationSummarizer;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.UserProfile;
import com.agent.platform.memory.UserProfileItem;
import com.agent.platform.procurement.application.ProcurementCaseContextRenderer;
import com.agent.platform.procurement.config.ProcurementSourcingExecutionProfileFactory;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementCaseStatus;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAgentContextManagerTests {

    @Test
    void disabledLongTermMemorySkipsRecallProfileAndSyntheticMemoryContext() {
        MemoryService memoryService = mock(MemoryService.class);
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(message("session-1", "message-1", 1,
                        AgentMessageType.USER, "current request", 4)),
                memoryService,
                null,
                text -> text == null ? 0 : text.length()
        );
        AgentExecutionProfile profile = profile("general", false);

        AgentContextView view = manager.project(
                "session-1", "user-1", "tenant-1", "request", 200, profile
        );

        assertFalse(view.metadata().containsKey("memoryContextIncluded")
                && Boolean.TRUE.equals(view.metadata().get("memoryContextIncluded")));
        assertEquals(false, view.metadata().get("memoryEnabled"));
        assertTrue(view.messages().stream().noneMatch(message ->
                message.content().contains("<memory_context>")));
        verifyNoInteractions(memoryService);
    }

    @Test
    void enabledLongTermMemoryKeepsRecallAndUserProfileProjection() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.recall("session-1", "user-1", "request", 8))
                .thenReturn(List.of(new MemorySearchResult(
                        "preference", "memory-1", "prefer delivery first", 0.95, Map.of())));
        when(memoryService.loadUserProfile("user-1")).thenReturn(new UserProfile(
                "user-1",
                List.of(new UserProfileItem("language", "中文", "test", Instant.now())),
                Instant.now()
        ));
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(), memoryService, null,
                text -> text == null ? 0 : text.length()
        );

        AgentContextView view = manager.project(
                "session-1", "user-1", "tenant-1", "request", 2_000, profile("general", true)
        );

        AgentMessage memoryContext = view.messages().stream()
                .filter(message -> message.messageId().equals("memory-context-session-1"))
                .findFirst()
                .orElseThrow();
        assertTrue(memoryContext.content().contains("prefer delivery first"));
        assertTrue(memoryContext.content().contains("profile.language=中文"));
        assertEquals(true, view.metadata().get("memoryEnabled"));
        assertEquals(true, view.metadata().get("memoryContextIncluded"));
        verify(memoryService).recall("session-1", "user-1", "request", 8);
        verify(memoryService).loadUserProfile("user-1");
    }

    @Test
    void procurementMemoryIsScopedToTheUserAndPlacedAfterCanonicalCaseBeforeRecentIntent() {
        MutableCaseStore caseStore = new MutableCaseStore(procurementCase(
                "tenant-1", "buyer-1", "conversation-b", 2, "new procurement case"
        ));
        MemoryService memoryService = new MemoryService() {
            @Override
            public void rememberLongTerm(String conversationId, String userId, MemoryMessage message) {
            }

            @Override
            public List<MemorySearchResult> recall(String conversationId, String userId,
                                                    String query, int limit) {
                return "buyer-1".equals(userId)
                        ? List.of(new MemorySearchResult("long_term", "memory-a",
                        "采购研发设备时通常交付优先", 0.95, Map.of("category", "preference")))
                        : List.of();
            }

            @Override
            public UserProfile loadUserProfile(String userId) {
                return UserProfile.empty(userId);
            }

            @Override
            public void upsertUserProfile(String userId, String key, String value,
                                          String source, Instant updatedAt) {
            }

            @Override
            public void clearConversation(String conversationId) {
            }

            @Override
            public void clearUserMemory(String userId) {
            }
        };
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(message("conversation-b", "recent-user", 1,
                        AgentMessageType.USER, "这次价格优先。", 3)),
                memoryService,
                new ProcurementCaseContextRenderer(caseStore, new ObjectMapper()),
                text -> 1
        );

        AgentContextView sameUser = manager.project(
                "conversation-b", "buyer-1", "tenant-1", "这次价格优先。", 100,
                procurementProfile()
        );

        int canonicalIndex = indexOfType(sameUser, AgentMessageType.CANONICAL_CONTEXT);
        int memoryIndex = indexOfMessage(sameUser, "memory-context-conversation-b");
        int recentIndex = indexOfMessage(sameUser, "recent-user");
        assertTrue(canonicalIndex < memoryIndex);
        assertTrue(memoryIndex < recentIndex);
        assertTrue(sameUser.messages().get(memoryIndex).content().contains("交付优先"));
        assertTrue(sameUser.messages().get(recentIndex).content().contains("这次价格优先"));
        assertEquals("case-2", sameUser.messages().get(canonicalIndex).metadata().get("caseId"));

        AgentContextView differentUser = manager.project(
                "conversation-c", "buyer-2", "tenant-1", "交付", 100, procurementProfile()
        );
        assertTrue(differentUser.messages().stream().noneMatch(message ->
                message.content().contains("<memory_context>")));
    }

    @Test
    void authoritativeProcurementContextIsRetainedAndConsumesBudgetBeforeRecentHistory() {
        MutableCaseStore caseStore = new MutableCaseStore(procurementCase(
                "tenant-1", "user-1", "session-1", 1, "CUDA workstation"
        ));
        TokenEstimator estimator = text -> text == null ? 0 : text.length();
        ProcurementCaseContextRenderer renderer = new ProcurementCaseContextRenderer(caseStore, new ObjectMapper());
        long canonicalTokens = estimator.estimate(renderer.render("tenant-1", "user-1", "session-1")
                .orElseThrow().content());
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(message("session-1", "history-1", 1,
                        AgentMessageType.USER, "old history", 7)),
                emptyMemoryService(), renderer, estimator
        );

        AgentContextView view = manager.project(
                "session-1", "user-1", "tenant-1", "request", canonicalTokens,
                procurementProfile()
        );

        AgentMessage canonical = view.messages().stream()
                .filter(message -> ProcurementCaseContextRenderer.SOURCE.equals(message.metadata().get("source")))
                .findFirst()
                .orElseThrow();
        assertEquals(AgentMessageType.CANONICAL_CONTEXT, canonical.type());
        assertEquals("case-1", canonical.metadata().get("caseId"));
        assertEquals(1L, canonical.metadata().get("caseVersion"));
        assertEquals(true, canonical.metadata().get("fresh"));
        assertEquals(false, canonical.metadata().get("trustedInstructions"));
        assertEquals(canonicalTokens, view.estimatedTokens());
        assertEquals(1, view.omittedMessages());
        assertTrue(view.messages().stream().noneMatch(message -> message.messageId().equals("history-1")));
        assertEquals(true, view.metadata().get("canonicalContextIncluded"));
        assertEquals(1, view.metadata().get("canonicalContextCount"));
    }

    @Test
    void repeatedProjectionReloadsTheUpdatedProcurementCaseVersion() {
        MutableCaseStore caseStore = new MutableCaseStore(procurementCase(
                "tenant-1", "user-1", "session-1", 1, "first description"
        ));
        TokenEstimator estimator = text -> 1;
        ProcurementCaseContextRenderer renderer = new ProcurementCaseContextRenderer(caseStore, new ObjectMapper());
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(), emptyMemoryService(), renderer, estimator
        );

        AgentContextView first = manager.project(
                "session-1", "user-1", "tenant-1", "request", 100, procurementProfile()
        );
        caseStore.set(procurementCase("tenant-1", "user-1", "session-1", 2, "updated description"));
        AgentContextView second = manager.project(
                "session-1", "user-1", "tenant-1", "request", 100, procurementProfile()
        );

        assertEquals(1L, first.messages().get(0).metadata().get("caseVersion"));
        assertEquals(2L, second.messages().get(0).metadata().get("caseVersion"));
        assertTrue(second.messages().get(0).content().contains("updated description"));
        assertNotEquals(first.messages().get(0).content(), second.messages().get(0).content());
        assertEquals(2, caseStore.findCalls);
    }

    @Test
    void nonProcurementProfileDoesNotInjectProcurementCase() {
        MutableCaseStore caseStore = new MutableCaseStore(procurementCase(
                "tenant-1", "user-1", "session-1", 1, "must not leak"
        ));
        DefaultAgentContextManager manager = manager(
                new MutableTimelineStore(), mock(MemoryService.class),
                new ProcurementCaseContextRenderer(caseStore, new ObjectMapper()),
                text -> 1
        );

        AgentContextView view = manager.project(
                "session-1", "user-1", "tenant-1", "request", 100,
                profile("another-agent", false)
        );

        assertTrue(view.messages().isEmpty());
        assertEquals(false, view.metadata().get("canonicalContextIncluded"));
        assertEquals(0, caseStore.findCalls);
    }

    @Test
    void compactPersistsSummaryAndReprojectsFreshProcurementCase() {
        MutableCaseStore caseStore = new MutableCaseStore(procurementCase(
                "tenant-1", "user-1", "session-1", 1, "before compact"
        ));
        TokenEstimator estimator = text -> text != null && text.startsWith("<procurement_case_context>") ? 1 : 5;
        MutableTimelineStore timeline = new MutableTimelineStore(
                message("session-1", "message-1", 1, AgentMessageType.USER, "old", 5),
                message("session-1", "message-2", 2, AgentMessageType.ASSISTANT_TEXT, "middle", 5),
                message("session-1", "message-3", 3, AgentMessageType.USER, "latest", 5)
        );
        AtomicReference<List<MemoryMessage>> summarized = new AtomicReference<>();
        ConversationSummarizer summarizer = (previous, messages, maxChars) -> {
            summarized.set(messages);
            return "summary-v1";
        };
        DefaultAgentContextManager manager = manager(
                timeline, emptyMemoryService(),
                new ProcurementCaseContextRenderer(caseStore, new ObjectMapper()),
                estimator, summarizer
        );

        manager.project("session-1", "user-1", "tenant-1", "request", 17, procurementProfile());
        caseStore.set(procurementCase("tenant-1", "user-1", "session-1", 2, "after compact"));
        AgentContextView compacted = manager.compact(
                "session-1", "user-1", "tenant-1", "run-1", "request", 17,
                "context_budget", procurementProfile()
        );

        AgentMessage canonical = compacted.messages().stream()
                .filter(message -> ProcurementCaseContextRenderer.SOURCE.equals(message.metadata().get("source")))
                .findFirst()
                .orElseThrow();
        assertEquals(AgentMessageType.CANONICAL_CONTEXT, canonical.type());
        AgentMessage persistedSummary = timeline.messages.stream()
                .filter(message -> message.type() == AgentMessageType.CONTEXT_SUMMARY)
                .findFirst()
                .orElseThrow();
        assertEquals(1, summarized.get().size());
        assertEquals("summary-v1", persistedSummary.content());
        assertEquals(1L, persistedSummary.metadata().get("coversThroughSequence"));
        assertEquals(2L, canonical.metadata().get("caseVersion"));
        assertEquals(true, compacted.metadata().get("compactionRequested"));
        assertEquals(true, compacted.metadata().get("compactionPerformed"));
        assertEquals(1, compacted.metadata().get("compactedMessageCount"));
        assertEquals(1L, compacted.metadata().get("compactionCoversThroughSequence"));
        assertEquals(1L, compacted.metadata().get("coversThroughSequence"));
    }

    @Test
    void twoRealCompactionsAdvanceCoverageMonotonicallyWithoutResummarizingCoveredMessages() {
        MutableTimelineStore timeline = new MutableTimelineStore(
                message("session-1", "message-1", 1, AgentMessageType.USER, "first user", 4),
                message("session-1", "message-2", 2, AgentMessageType.ASSISTANT_TEXT, "first answer", 4),
                toolCall("session-1", "call-1", 3, "lookup", 4),
                toolResult("session-1", "result-1", 4, "call-1", "lookup", "first result", 4),
                message("session-1", "message-5", 5, AgentMessageType.USER, "post summary user", 5),
                message("session-1", "message-6", 6, AgentMessageType.ASSISTANT_TEXT, "post summary answer", 5)
        );
        List<List<MemoryMessage>> summarized = new ArrayList<>();
        ConversationSummarizer summarizer = (previous, messages, maxChars) -> {
            summarized.add(messages);
            return "summary-" + summarized.size();
        };
        AgentCanonicalContextProvider canonicalProvider = (tenantId, userId, conversationId, profile) ->
                Optional.of(new AgentCanonicalContextProvider.CanonicalContext(
                        "canonical-1", "canonical payload", Map.of("source", "test")));
        DefaultAgentContextManager manager = manager(
                timeline, mock(MemoryService.class), canonicalProvider, text -> 1, summarizer
        );

        AgentContextView first = manager.compact(
                "session-1", "user-1", "tenant-1", "run-1", "request", 15,
                "context_budget", profile("general", false)
        );
        List<AgentMessage> summariesAfterFirst = timeline.messages.stream()
                .filter(message -> message.type() == AgentMessageType.CONTEXT_SUMMARY)
                .toList();
        AgentMessage firstSummary = summariesAfterFirst.get(0);
        long firstCoverage = ((Number) firstSummary.metadata().get("coversThroughSequence")).longValue();

        timeline.add(message("session-1", "message-8", 8, AgentMessageType.USER, "second user", 5));
        timeline.add(message("session-1", "message-9", 9, AgentMessageType.ASSISTANT_TEXT, "second answer", 5));
        timeline.add(message("session-1", "message-10", 10, AgentMessageType.USER, "latest second turn", 5));

        AgentContextView second = manager.compact(
                "session-1", "user-1", "tenant-1", "run-2", "request", 15,
                "context_budget", profile("general", false)
        );
        List<AgentMessage> summaries = timeline.messages.stream()
                .filter(message -> message.type() == AgentMessageType.CONTEXT_SUMMARY)
                .sorted(Comparator.comparingLong(AgentMessage::sequence))
                .toList();
        AgentMessage secondSummary = summaries.get(1);
        long secondPreviousCoverage = ((Number) secondSummary.metadata()
                .get("previousCoveredThroughSequence")).longValue();
        long secondCoverage = ((Number) secondSummary.metadata().get("coversThroughSequence")).longValue();

        assertTrue((Boolean) first.metadata().get("compactionPerformed"));
        assertTrue((Boolean) second.metadata().get("compactionPerformed"));
        assertTrue(first.messages().stream().anyMatch(message ->
                message.type() == AgentMessageType.CANONICAL_CONTEXT));
        assertEquals(2, summaries.size());
        assertEquals(firstCoverage, secondPreviousCoverage);
        assertTrue(secondCoverage > firstCoverage);
        assertTrue(summarized.get(0).stream().anyMatch(message -> message.content().contains("first user")));
        assertTrue(summarized.get(0).stream().anyMatch(message -> message.content().contains("first answer")));
        assertTrue(summarized.get(0).stream().anyMatch(message -> message.content().contains("first result")));
        assertTrue(summarized.get(1).stream().noneMatch(message -> message.content().contains("first user")));
        assertTrue(summarized.get(1).stream().noneMatch(message -> message.content().contains("first answer")));
        assertTrue(summarized.get(1).stream().noneMatch(message -> message.content().contains("first result")));
        assertTrue(summarized.stream().flatMap(List::stream).noneMatch(message ->
                message.content().contains("canonical payload")));
        assertTrue(timeline.messages.stream().anyMatch(message -> message.messageId().equals("message-1")));
        assertTrue(timeline.messages.stream().anyMatch(message -> message.messageId().equals("call-1")));
        assertTrue(timeline.messages.stream().anyMatch(message -> message.messageId().equals("result-1")));
        assertEquals(List.of("assistant_tool_call", "tool_result"),
                summarized.get(0).stream().filter(message -> message.content().contains("call-1"))
                        .map(MemoryMessage::role).toList());
    }

    @Test
    void summaryCoverageExcludesCoveredHistoryAndUsesTheLatestCoverage() {
        MutableTimelineStore timeline = new MutableTimelineStore(
                message("session-1", "old", 1, AgentMessageType.USER, "covered history", 2),
                message("session-1", "new", 2, AgentMessageType.ASSISTANT_TEXT, "new history", 2),
                summary("session-1", "summary-1", 3, "summary one", 1)
        );
        DefaultAgentContextManager manager = manager(
                timeline, mock(MemoryService.class), null, text -> 1
        );

        AgentContextView first = manager.project("session-1", "user-1", "", 100);
        assertTrue(first.messages().stream().noneMatch(message -> message.messageId().equals("old")));
        assertTrue(first.messages().stream().anyMatch(message -> message.messageId().equals("new")));
        assertEquals(1L, first.metadata().get("coversThroughSequence"));
        assertEquals(0, first.omittedMessages());

        timeline.add(summary("session-1", "summary-2", 4, "summary two", 2));
        AgentContextView second = manager.project("session-1", "user-1", "", 100);
        assertTrue(second.messages().stream().noneMatch(message -> message.messageId().equals("new")));
        assertEquals(List.of("summary-2"), second.messages().stream()
                .map(AgentMessage::messageId).toList());
        assertEquals(2L, second.metadata().get("coversThroughSequence"));
    }

    @Test
    void toolCallAndToolResultRemainAnAtomicUnitAndOrphansAreNotProjected() {
        MutableTimelineStore timeline = new MutableTimelineStore(
                message("session-1", "old", 1, AgentMessageType.USER, "old", 5),
                toolCall("session-1", "call-1", 2, "lookup", 4),
                toolResult("session-1", "result-1", 3, "call-1", "lookup", "ok", 4),
                orphanToolCall("session-1", "orphan", 4, "lookup", 4)
        );
        DefaultAgentContextManager manager = manager(
                timeline, mock(MemoryService.class), null, text -> 1
        );

        AgentContextView view = manager.project("session-1", "user-1", "", 8);

        assertEquals(List.of(AgentMessageType.ASSISTANT_TOOL_CALL, AgentMessageType.TOOL_RESULT),
                view.messages().stream().map(AgentMessage::type).toList());
        assertEquals(List.of("call-1", "call-1"), view.messages().stream()
                .map(AgentMessage::toolCallId).toList());
        assertTrue(view.messages().stream().noneMatch(message -> message.toolCallId().equals("orphan")));
        assertEquals(2, view.omittedMessages());
        assertEquals(8, view.estimatedTokens());
    }

    private DefaultAgentContextManager manager(AgentTimelineStore timelineStore,
                                                MemoryService memoryService,
                                                AgentCanonicalContextProvider renderer,
                                                TokenEstimator estimator) {
        return manager(timelineStore, memoryService, renderer, estimator,
                (previous, messages, maxChars) -> "summary");
    }

    private DefaultAgentContextManager manager(AgentTimelineStore timelineStore,
                                                MemoryService memoryService,
                                                AgentCanonicalContextProvider renderer,
                                                TokenEstimator estimator,
                                                ConversationSummarizer summarizer) {
        AgentProperties properties = new AgentProperties();
        if (renderer == null) {
            return new DefaultAgentContextManager(
                    timelineStore, estimator, memoryService, summarizer, properties
            );
        }
        return new DefaultAgentContextManager(
                timelineStore, estimator, memoryService, summarizer, properties, List.of(renderer)
        );
    }

    private MemoryService emptyMemoryService() {
        return new MemoryService() {
            @Override
            public void rememberLongTerm(String conversationId, String userId, MemoryMessage message) {
            }

            @Override
            public List<MemorySearchResult> recall(String conversationId, String userId,
                                                    String query, int limit) {
                return List.of();
            }

            @Override
            public UserProfile loadUserProfile(String userId) {
                return UserProfile.empty(userId);
            }

            @Override
            public void upsertUserProfile(String userId, String key, String value,
                                          String source, Instant updatedAt) {
            }

            @Override
            public void clearConversation(String conversationId) {
            }

            @Override
            public void clearUserMemory(String userId) {
            }
        };
    }

    private int indexOfType(AgentContextView view, AgentMessageType type) {
        for (int index = 0; index < view.messages().size(); index++) {
            if (view.messages().get(index).type() == type) return index;
        }
        return -1;
    }

    private int indexOfMessage(AgentContextView view, String messageId) {
        for (int index = 0; index < view.messages().size(); index++) {
            if (messageId.equals(view.messages().get(index).messageId())) return index;
        }
        return -1;
    }

    private AgentExecutionProfile profile(String name, boolean memoryEnabled) {
        return new AgentExecutionProfile(name, "prompt", Set.of(),
                new AgentRunLimits(4, 4, 4, 1_000, 1_000, 0, 10_000), memoryEnabled);
    }

    private AgentExecutionProfile procurementProfile() {
        return new ProcurementSourcingExecutionProfileFactory().createProfile();
    }

    private static AgentMessage message(String sessionId,
                                        String messageId,
                                        long sequence,
                                        AgentMessageType type,
                                        String content,
                                        long estimatedTokens) {
        return new AgentMessage(messageId, sessionId, "run-1", sequence, type, content,
                "", "", Map.of(), Map.of(), estimatedTokens, Instant.now());
    }

    private static AgentMessage toolCall(String sessionId,
                                         String messageId,
                                         long sequence,
                                         String toolName,
                                         long estimatedTokens) {
        return new AgentMessage(messageId, sessionId, "run-1", sequence,
                AgentMessageType.ASSISTANT_TOOL_CALL, "", "call-1", toolName,
                Map.of("key", "value"), Map.of(), estimatedTokens, Instant.now());
    }

    private static AgentMessage toolResult(String sessionId,
                                           String messageId,
                                           long sequence,
                                           String toolCallId,
                                           String toolName,
                                           String content,
                                           long estimatedTokens) {
        return new AgentMessage(messageId, sessionId, "run-1", sequence,
                AgentMessageType.TOOL_RESULT, content, toolCallId, toolName,
                Map.of(), Map.of("success", true), estimatedTokens, Instant.now());
    }

    private static AgentMessage orphanToolCall(String sessionId,
                                               String messageId,
                                               long sequence,
                                               String toolName,
                                               long estimatedTokens) {
        return new AgentMessage(messageId, sessionId, "run-1", sequence,
                AgentMessageType.ASSISTANT_TOOL_CALL, "", "orphan", toolName,
                Map.of("key", "value"), Map.of(), estimatedTokens, Instant.now());
    }

    private static AgentMessage summary(String sessionId,
                                        String messageId,
                                        long sequence,
                                        String content,
                                        long coversThroughSequence) {
        return new AgentMessage(messageId, sessionId, "run-1", sequence,
                AgentMessageType.CONTEXT_SUMMARY, content, "", "", Map.of(),
                Map.of("coversThroughSequence", coversThroughSequence), 1, Instant.now());
    }

    private static ProcurementCase procurementCase(String tenantId,
                                                   String userId,
                                                   String conversationId,
                                                   long version,
                                                   String description) {
        Instant now = Instant.now();
        return new ProcurementCase(
                "case-" + version, tenantId, conversationId, userId,
                ProcurementCaseStatus.SOURCING,
                new ProcurementCaseState(
                        "计算工作站", description, 2, new BigDecimal("10000"), "CNY", 14,
                        Map.of("gpuMemoryMinGb", "24"), Map.of("deliveryPriority", "HIGH"),
                        Set.of(), List.of(), "SOURCING"
                ),
                now, now, version, "input-" + version
        );
    }

    private static final class MutableCaseStore implements ProcurementCaseStore {
        private ProcurementCase value;
        private int findCalls;

        private MutableCaseStore(ProcurementCase value) {
            this.value = value;
        }

        private void set(ProcurementCase value) {
            this.value = value;
        }

        @Override
        public Optional<ProcurementCase> findByTenantUserAndConversationId(String tenantId,
                                                                             String userId,
                                                                             String conversationId) {
            findCalls++;
            if (value != null && value.tenantId().equals(tenantId)
                    && value.userId().equals(userId)
                    && value.conversationId().equals(conversationId)) {
                return Optional.of(value);
            }
            return Optional.empty();
        }

        @Override
        public boolean createIfAbsent(ProcurementCase procurementCase) {
            return false;
        }

        @Override
        public boolean saveIfVersion(ProcurementCase procurementCase, long expectedVersion) {
            value = procurementCase;
            return true;
        }
    }

    private static final class MutableTimelineStore implements AgentTimelineStore {
        private final List<AgentMessage> messages = new ArrayList<>();
        private long nextSequence;

        private MutableTimelineStore(AgentMessage... initialMessages) {
            for (AgentMessage message : initialMessages) {
                add(message);
            }
        }

        private synchronized void add(AgentMessage message) {
            messages.add(message);
            nextSequence = Math.max(nextSequence, message.sequence());
        }

        @Override
        public synchronized AgentSession openSession(String sessionId, String userId) {
            return new AgentSession(sessionId, userId, nextSequence + 1, 1, 0, Instant.now(), Instant.now());
        }

        @Override
        public synchronized Optional<AgentSession> findSession(String sessionId) {
            return Optional.empty();
        }

        @Override
        public synchronized List<AgentMessage> appendMessages(String sessionId,
                                                               String userId,
                                                               String runId,
                                                               List<AgentMessageDraft> drafts) {
            List<AgentMessage> appended = new ArrayList<>();
            for (AgentMessageDraft draft : drafts) {
                AgentMessage message = new AgentMessage(
                        "appended-" + (nextSequence + 1), sessionId, runId, ++nextSequence,
                        draft.type(), draft.content(), draft.toolCallId(), draft.toolName(),
                        draft.arguments(), draft.metadata(), draft.estimatedTokens(), Instant.now()
                );
                messages.add(message);
                appended.add(message);
            }
            return List.copyOf(appended);
        }

        @Override
        public synchronized List<AgentMessage> loadMessages(String sessionId, int limit) {
            return messages.stream()
                    .filter(message -> message.sessionId().equals(sessionId))
                    .sorted(Comparator.comparingLong(AgentMessage::sequence))
                    .limit(limit)
                    .toList();
        }

        @Override
        public AgentEvent appendEvent(String sessionId,
                                      String userId,
                                      String runId,
                                      AgentEventDraft event) {
            return new AgentEvent("event", runId, sessionId, 1, event.type(), event.content(),
                    event.payload(), Instant.now());
        }

        @Override
        public List<AgentEvent> loadEvents(String runId, int limit) {
            return List.of();
        }

        @Override
        public List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit) {
            return List.of();
        }
    }
}
