package com.agent.platform.workbench.target;

import com.agent.platform.workbench.model.WorkCommandType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class ExecutionCommandCapabilityRegistry {

    private final Map<ExecutionTargetId, ExecutionCommandCapabilities> capabilities;

    public ExecutionCommandCapabilityRegistry() {
        EnumMap<ExecutionTargetId, ExecutionCommandCapabilities> values =
                new EnumMap<>(ExecutionTargetId.class);
        values.put(ExecutionTargetId.GENERAL_AGENT, runtimeCapabilities(
                Set.of("NO_GENERAL_ADD_INPUT_CHECKPOINT")));
        values.put(ExecutionTargetId.ORDERCARE_CASE, runtimeCapabilities(
                Set.of("DO_NOT_MUTATE_APPROVED_PROPOSAL", "DO_NOT_ROLL_BACK_SUBMITTED_SIDE_EFFECT")));
        values.put(ExecutionTargetId.INCIDENT_INVESTIGATION, unsupportedCapabilities(
                Set.of("NO_INCIDENT_LEVEL_COMMAND_SERVICE", "DO_NOT_BROADCAST_INPUT_TO_SPECIALISTS")));
        values.put(ExecutionTargetId.INCIDENT_RECOVERY_PLAN, unsupportedCapabilities(
                Set.of("NO_PLAN_LEVEL_COMMAND_SERVICE", "CONTINUE_UNKNOWN_RECONCILIATION")));
        capabilities = Map.copyOf(values);
    }

    /**
     * 返回某个执行目标（如 ORDERCARE_CASE）对每一种命令（暂停/继续/取消/放弃）支持到什么程度（SUPPORTED_EXISTING_RUNTIME / PRODUCT_ONLY / UNSUPPORTED），以及它有哪些约束
     */
    public ExecutionCommandCapabilities require(ExecutionTargetId targetId) {
        ExecutionCommandCapabilities value = capabilities.get(targetId);
        if (value == null) {
            throw new IllegalArgumentException("execution command target is not registered: " + targetId);
        }
        return value;
    }

    private ExecutionCommandCapabilities runtimeCapabilities(Set<String> constraints) {
        // 把所有命令类型（WorkCommandType 的所有枚举值）都先塞进 map，默认值全部是 UNSUPPORTED（不支持）
        EnumMap<WorkCommandType, ExecutionCommandSupport> commands = defaults();
        commands.put(WorkCommandType.PAUSE_ACTIVE_WORK, ExecutionCommandSupport.SUPPORTED_EXISTING_RUNTIME);
        commands.put(WorkCommandType.RESUME_ACTIVE_WORK, ExecutionCommandSupport.SUPPORTED_EXISTING_RUNTIME);
        commands.put(WorkCommandType.CANCEL_ACTIVE_WORK, ExecutionCommandSupport.SUPPORTED_EXISTING_RUNTIME);
        commands.put(WorkCommandType.ABANDON_ACTIVE_WORK, ExecutionCommandSupport.PRODUCT_ONLY);
        return new ExecutionCommandCapabilities(commands, constraints);
    }

    private ExecutionCommandCapabilities unsupportedCapabilities(Set<String> constraints) {
        EnumMap<WorkCommandType, ExecutionCommandSupport> commands = defaults();
        commands.put(WorkCommandType.ABANDON_ACTIVE_WORK, ExecutionCommandSupport.PRODUCT_ONLY);
        return new ExecutionCommandCapabilities(commands, constraints);
    }

    /**
     * 把所有命令类型（WorkCommandType 的所有枚举值）都先塞进 map，默认值全部是 UNSUPPORTED（不支持）
     */
    private EnumMap<WorkCommandType, ExecutionCommandSupport> defaults() {
        EnumMap<WorkCommandType, ExecutionCommandSupport> commands = new EnumMap<>(WorkCommandType.class);
        for (WorkCommandType command : WorkCommandType.values()) {
            commands.put(command, ExecutionCommandSupport.UNSUPPORTED);
        }
        return commands;
    }
}
