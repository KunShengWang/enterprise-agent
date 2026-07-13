package com.agent.platform.web;

import com.agent.platform.common.ApiResponse;
import com.agent.platform.common.ErrorCode;
import com.agent.platform.skill.SkillDefinition;
import com.agent.platform.skill.SkillRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/agent/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @GetMapping
    public Mono<ApiResponse<List<SkillDefinition>>> list() {
        return Mono.fromSupplier(() -> ApiResponse.success(skillRegistry.list()));
    }

    @GetMapping("/{name}")
    public Mono<ApiResponse<SkillDefinition>> find(@PathVariable String name) {
        return Mono.fromSupplier(() -> skillRegistry.find(name)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.failure(ErrorCode.NOT_FOUND, "skill not found: " + name)));
    }

}
