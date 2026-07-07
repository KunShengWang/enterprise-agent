package com.agent.platform.memory;

import com.agent.platform.config.MemoryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.memory", name = "mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMemoryService implements MemoryService {

    private static final String DEFAULT_CONVERSATION_ID = "default-conversation";

    private static final String DEFAULT_USER_ID = "anonymous-user";

    private final MemoryProperties memoryProperties;

    private final ConversationSummarizer conversationSummarizer;

    private final MemoryExtractor memoryExtractor;

    private final MemoryRecallScorer recallScorer;

    private final ConcurrentMap<String, List<MemoryMessage>> messages = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, String> summaries = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Integer> summarizedMessageCounts = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, List<LongTermMemory>> longTermMemories = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Map<String, UserProfileItem>> userProfiles = new ConcurrentHashMap<>();

    public InMemoryMemoryService(MemoryProperties memoryProperties,
                                 ConversationSummarizer conversationSummarizer,
                                 MemoryExtractor memoryExtractor,
                                 MemoryRecallScorer recallScorer) {
        this.memoryProperties = memoryProperties;
        this.conversationSummarizer = conversationSummarizer;
        this.memoryExtractor = memoryExtractor;
        this.recallScorer = recallScorer;
    }

    @Override
    public ConversationMemory load(String conversationId, String userId, String query) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        List<MemoryMessage> allMessages = messages.getOrDefault(normalizedConversationId, List.of());
        List<MemoryMessage> recentMessages = recentWindow(allMessages, memoryProperties.getWindowSize());
        List<LongTermMemory> longTerm = recentLongTermMemories(normalizedConversationId, normalizedUserId, memoryProperties.getLongTermLimit());
        UserProfile profile = loadUserProfile(normalizedUserId);
        List<MemorySearchResult> recalled = isBlank(query)
                ? List.of()
                : recall(normalizedConversationId, normalizedUserId, query, memoryProperties.getRecallLimit());
        return new ConversationMemory(
                normalizedConversationId,
                normalizedUserId,
                recentMessages,
                summaries.getOrDefault(normalizedConversationId, ""),
                longTerm,
                profile,
                recalled
        );
    }

    @Override
    public void append(String conversationId, String userId, MemoryMessage message) {
        if (message == null || isBlank(message.content())) {
            return;
        }
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        MemoryMessage effectiveMessage = new MemoryMessage(
                normalizeRole(message.role()),
                message.content().trim(),
                message.createdAt() == null ? Instant.now() : message.createdAt()
        );
        List<MemoryMessage> updatedMessages = messages.compute(normalizedConversationId, (key, current) -> {
            List<MemoryMessage> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.add(effectiveMessage);
            return next;
        });
        extractAndStore(normalizedConversationId, normalizedUserId, effectiveMessage);
        updateSummaryIfNeeded(normalizedConversationId, updatedMessages);
    }

    @Override
    public List<MemorySearchResult> recall(String conversationId, String userId, String query, int limit) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        int effectiveLimit = Math.max(1, limit);
        List<MemorySearchResult> results = new ArrayList<>();
        String summary = summaries.get(normalizedConversationId);
        addScoredResult(results, query, "summary", normalizedConversationId, summary, Map.of("conversationId", normalizedConversationId));

        List<MemoryMessage> allMessages = messages.getOrDefault(normalizedConversationId, List.of());
        int fromIndex = Math.max(0, allMessages.size() - 80);
        for (int index = fromIndex; index < allMessages.size(); index++) {
            MemoryMessage message = allMessages.get(index);
            addScoredResult(results, query, "message", normalizedConversationId + ":" + index, message.content(),
                    Map.of("role", message.role(), "createdAt", message.createdAt()));
        }
        for (LongTermMemory memory : recentLongTermMemories(normalizedConversationId, normalizedUserId, memoryProperties.getLongTermLimit())) {
            addScoredResult(results, query, "long_term", memory.memoryId(), memory.content(),
                    Map.of("category", memory.category(), "confidence", memory.confidence()));
        }
        for (UserProfileItem item : loadUserProfile(normalizedUserId).items()) {
            addScoredResult(results, query, "user_profile", normalizedUserId + ":" + item.key(), item.key() + "=" + item.value(),
                    Map.of("key", item.key(), "source", item.source()));
        }
        return results.stream()
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(MemorySearchResult::score).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    @Override
    public MemorySnapshot snapshot(String conversationId, String userId, String query, int limit) {
        ConversationMemory memory = load(conversationId, userId, query);
        return new MemorySnapshot(
                memory.conversationId(),
                memory.userId(),
                recentWindow(messages.getOrDefault(memory.conversationId(), List.of()), Math.max(1, limit)),
                memory.summary(),
                memory.longTermMemories(),
                memory.userProfile(),
                memory.recalledMemories(),
                stats(memory.conversationId(), memory.userId())
        );
    }

    @Override
    public MemoryStats stats(String conversationId, String userId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedUserId = normalizeUserId(userId);
        return new MemoryStats(
                "memory",
                normalizedConversationId,
                normalizedUserId,
                messages.getOrDefault(normalizedConversationId, List.of()).size(),
                summaries.containsKey(normalizedConversationId) ? 1 : 0,
                recentLongTermMemories(normalizedConversationId, normalizedUserId, Integer.MAX_VALUE).size(),
                loadUserProfile(normalizedUserId).items().size()
        );
    }

    @Override
    public UserProfile loadUserProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        List<UserProfileItem> items = userProfiles.getOrDefault(normalizedUserId, Map.of())
                .values()
                .stream()
                .sorted(Comparator.comparing(UserProfileItem::key))
                .limit(Math.max(1, memoryProperties.getProfileItemLimit()))
                .toList();
        Instant updatedAt = items.stream()
                .map(UserProfileItem::updatedAt)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new UserProfile(normalizedUserId, items, updatedAt);
    }

    @Override
    public void upsertUserProfile(String userId, String key, String value, String source, Instant updatedAt) {
        String normalizedUserId = normalizeUserId(userId);
        if (isBlank(key) || isBlank(value)) {
            return;
        }
        userProfiles.compute(normalizedUserId, (ignored, current) -> {
            Map<String, UserProfileItem> next = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
            next.put(key.trim(), new UserProfileItem(key.trim(), value.trim(), blankToDefault(source, "manual"), updatedAt == null ? Instant.now() : updatedAt));
            return next;
        });
    }

    @Override
    public void clearConversation(String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        messages.remove(normalizedConversationId);
        summaries.remove(normalizedConversationId);
        summarizedMessageCounts.remove(normalizedConversationId);
        longTermMemories.remove(normalizedConversationId);
    }

    @Override
    public void clearUserMemory(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        userProfiles.remove(normalizedUserId);
        longTermMemories.replaceAll((conversationId, current) -> current.stream()
                .filter(memory -> !normalizedUserId.equals(memory.userId()))
                .toList());
    }

    private void extractAndStore(String conversationId, String userId, MemoryMessage message) {
        MemoryExtraction extraction = memoryExtractor.extract(conversationId, userId, message);
        if (!extraction.longTermMemories().isEmpty()) {
            longTermMemories.compute(conversationId, (key, current) -> {
                List<LongTermMemory> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
                for (LongTermMemoryDraft draft : extraction.longTermMemories()) {
                    Instant now = Instant.now();
                    next.add(new LongTermMemory(
                            UUID.randomUUID().toString(),
                            conversationId,
                            userId,
                            blankToDefault(draft.category(), "fact"),
                            draft.content(),
                            draft.confidence(),
                            now,
                            now
                    ));
                }
                return next;
            });
        }
        for (UserProfileItem item : extraction.profileItems()) {
            upsertUserProfile(userId, item.key(), item.value(), item.source(), item.updatedAt());
        }
    }

    private void updateSummaryIfNeeded(String conversationId, List<MemoryMessage> allMessages) {
        int trigger = Math.max(2, memoryProperties.getSummaryTriggerMessages());
        int summarizedCount = summarizedMessageCounts.getOrDefault(conversationId, 0);
        int unsummarizedCount = allMessages.size() - summarizedCount;
        if (unsummarizedCount < trigger) {
            return;
        }
        int windowStart = Math.max(summarizedCount, allMessages.size() - memoryProperties.getWindowSize());
        if (windowStart <= summarizedCount) {
            return;
        }
        List<MemoryMessage> messagesToSummarize = new ArrayList<>(allMessages.subList(summarizedCount, windowStart));
        if (messagesToSummarize.isEmpty()) {
            return;
        }
        String nextSummary = conversationSummarizer.summarize(
                summaries.getOrDefault(conversationId, ""),
                messagesToSummarize,
                memoryProperties.getSummaryMaxChars()
        );
        summaries.put(conversationId, nextSummary);
        summarizedMessageCounts.put(conversationId, windowStart);
    }

    private List<LongTermMemory> recentLongTermMemories(String conversationId, String userId, int limit) {
        return longTermMemories.values()
                .stream()
                .flatMap(List::stream)
                .filter(memory -> conversationId.equals(memory.conversationId()) || userId.equals(memory.userId()))
                .sorted(Comparator.comparing(LongTermMemory::updatedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<MemoryMessage> recentWindow(List<MemoryMessage> allMessages, int limit) {
        if (allMessages == null || allMessages.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = Math.max(1, limit);
        int fromIndex = Math.max(0, allMessages.size() - effectiveLimit);
        return List.copyOf(allMessages.subList(fromIndex, allMessages.size()));
    }

    private void addScoredResult(List<MemorySearchResult> results, String query, String type, String id, String content, Map<String, Object> metadata) {
        if (isBlank(content)) {
            return;
        }
        double score = recallScorer.score(query, content);
        if (score > 0) {
            results.add(new MemorySearchResult(type, id, content, score, metadata));
        }
    }

    private String normalizeConversationId(String conversationId) {
        return blankToDefault(conversationId, DEFAULT_CONVERSATION_ID);
    }

    private String normalizeUserId(String userId) {
        return blankToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeRole(String role) {
        return blankToDefault(role, "user").toLowerCase();
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
