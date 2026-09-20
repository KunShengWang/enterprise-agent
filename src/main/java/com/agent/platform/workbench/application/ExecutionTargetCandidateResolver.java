package com.agent.platform.workbench.application;

import com.agent.platform.common.BusinessRetirementPolicy;

import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.target.ExecutionTargetDefinition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies deterministic business boundaries before model routing.
 * The resolver does not grant permissions; it only narrows the already enabled target catalog.
 */
@Component
public class ExecutionTargetCandidateResolver {

    static final String POLICY_VERSION = "execution-target-candidates-v2-retirement";

    public Resolution resolve(String originalGoal, List<ExecutionTargetDefinition> enabledTargets) {
        List<ExecutionTargetDefinition> active = enabledTargets == null ? List.of() : enabledTargets.stream()
                .filter(target -> target.enabled() && target.targetId().executable()).toList();
        if (active.isEmpty()) throw new IllegalArgumentException("enabled targets are required");
        if (BusinessRetirementPolicy.retiredInput(originalGoal)) {
            return Resolution.deterministic(active, deterministicResult(originalGoal,
                    new ExecutionDecision("", 1.0, BusinessRetirementPolicy.CODE,
                            Map.of(), List.of(), BusinessRetirementPolicy.MESSAGE)),
                    BusinessRetirementPolicy.CODE);
        }
        return Resolution.model(active, "ACTIVE_TARGETS_ONLY");
    }

    private RouterModelResult deterministicResult(String goal, ExecutionDecision decision) {
        String promptDigest = sha256(POLICY_VERSION + "|" + goal);
        String resultDigest = sha256(decision.targetId() + "|" + decision.reason()
                + "|" + decision.extractedInputs() + "|" + decision.missingInputs());
        return new RouterModelResult(decision, POLICY_VERSION, promptDigest, resultDigest,
                "", 0, 0, 0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("failed to digest deterministic route decision", exception);
        }
    }

    public record Resolution(
            List<ExecutionTargetDefinition> candidates,
            Optional<RouterModelResult> deterministicResult,
            String clarificationReason,
            String policyReason
    ) {
        public Resolution {
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("candidate targets are required");
            }
            candidates = List.copyOf(candidates);
            deterministicResult = deterministicResult == null ? Optional.empty() : deterministicResult;
            clarificationReason = clarificationReason == null ? "" : clarificationReason.trim();
            policyReason = policyReason == null ? "" : policyReason.trim();
        }

        static Resolution deterministic(List<ExecutionTargetDefinition> candidates,
                                        RouterModelResult result,
                                        String policyReason) {
            return new Resolution(candidates, Optional.of(result), "", policyReason);
        }

        static Resolution model(List<ExecutionTargetDefinition> candidates, String policyReason) {
            return new Resolution(candidates, Optional.empty(), "", policyReason);
        }

        static Resolution clarification(List<ExecutionTargetDefinition> candidates,
                                        RouterModelResult result,
                                        String reason,
                                        String policyReason) {
            return new Resolution(candidates, Optional.of(result), reason, policyReason);
        }

        public boolean retiredBusiness() { return BusinessRetirementPolicy.CODE.equals(policyReason); }

        public boolean requiresClarification() {
            return !clarificationReason.isBlank();
        }

        public boolean allows(String targetId) {
            return candidates.stream().anyMatch(candidate -> candidate.targetId().name().equals(targetId));
        }
    }

}
