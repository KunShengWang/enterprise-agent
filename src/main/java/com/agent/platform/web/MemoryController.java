package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.memory.MemoryMessage;
import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.MemorySnapshot;
import com.agent.platform.memory.MemoryStats;
import com.agent.platform.memory.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/agent/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/conversations/{conversationId}")
    public Mono<ApiResponse<MemorySnapshot>> snapshot(@PathVariable String conversationId,
                                                      @RequestParam(required = false) String userId,
                                                      @RequestParam(required = false) String query,
                                                      @RequestParam(defaultValue = "30") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(memoryService.snapshot(conversationId, userId, query, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public Mono<ApiResponse<MemorySnapshot>> appendMessage(@PathVariable String conversationId,
                                                           @Valid @RequestBody AppendMemoryMessageRequest request) {
        return Mono.fromSupplier(() -> {
                    memoryService.append(conversationId, request.userId(), new MemoryMessage(
                            request.role(),
                            request.content(),
                            request.createdAt() == null ? Instant.now() : request.createdAt()
                    ));
                    return ApiResponse.success(memoryService.snapshot(conversationId, request.userId(), request.content(), 30));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/conversations/{conversationId}/recall")
    public Mono<ApiResponse<List<MemorySearchResult>>> recall(@PathVariable String conversationId,
                                                              @RequestParam(required = false) String userId,
                                                              @RequestParam String query,
                                                              @RequestParam(defaultValue = "8") int limit) {
        return Mono.fromSupplier(() -> ApiResponse.success(memoryService.recall(conversationId, userId, query, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/conversations/{conversationId}/stats")
    public Mono<ApiResponse<MemoryStats>> stats(@PathVariable String conversationId,
                                                @RequestParam(required = false) String userId) {
        return Mono.fromSupplier(() -> ApiResponse.success(memoryService.stats(conversationId, userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Mono<ApiResponse<String>> clearConversation(@PathVariable String conversationId) {
        return Mono.fromSupplier(() -> {
                    memoryService.clearConversation(conversationId);
                    return ApiResponse.success("conversation memory cleared");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/users/{userId}/profile")
    public Mono<ApiResponse<UserProfile>> userProfile(@PathVariable String userId) {
        return Mono.fromSupplier(() -> ApiResponse.success(memoryService.loadUserProfile(userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/users/{userId}/profile")
    public Mono<ApiResponse<UserProfile>> upsertUserProfile(@PathVariable String userId,
                                                            @Valid @RequestBody UpsertUserProfileRequest request) {
        return Mono.fromSupplier(() -> {
                    memoryService.upsertUserProfile(userId, request.key(), request.value(), request.source(), Instant.now());
                    return ApiResponse.success(memoryService.loadUserProfile(userId));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/users/{userId}")
    public Mono<ApiResponse<String>> clearUserMemory(@PathVariable String userId) {
        return Mono.fromSupplier(() -> {
                    memoryService.clearUserMemory(userId);
                    return ApiResponse.success("user memory cleared");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record AppendMemoryMessageRequest(
            String userId,
            @NotBlank
            String role,
            @NotBlank
            String content,
            Instant createdAt
    ) {
    }

    public record UpsertUserProfileRequest(
            @NotBlank
            String key,
            @NotBlank
            String value,
            String source
    ) {
    }
}
