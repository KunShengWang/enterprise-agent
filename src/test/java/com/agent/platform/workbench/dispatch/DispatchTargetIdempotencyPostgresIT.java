package com.agent.platform.workbench.dispatch;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.runtime.AgentRunRecord;
import com.agent.platform.runtime.AgentRunStore;
import com.agent.platform.runtime.JdbcAgentRuntimeStore;
import com.agent.platform.storage.AgentStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_POSTGRES_IT", matches = "true")
class DispatchTargetIdempotencyPostgresIT {

    private final AgentStorageProperties storage = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> runIds = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = openConnection()) {
            for (String runId : runIds) execute(connection, "DELETE FROM agent_run_state WHERE run_id=?", runId);
        }
    }

    @Test
    void agentRunStoreBindsOneDispatchRequestToExactlyOneRun() {
        AgentRunStore store = new JdbcAgentRuntimeStore(storage, objectMapper);
        String dispatchRequestId = "dispatch-run-" + UUID.randomUUID();
        AgentRunRecord first = run("run-" + UUID.randomUUID(), dispatchRequestId);
        AgentRunRecord duplicateTarget = run("run-" + UUID.randomUUID(), dispatchRequestId);
        runIds.add(first.runId());
        runIds.add(duplicateTarget.runId());

        store.create(first);

        assertEquals(first.runId(), store.findByDispatchRequestId(dispatchRequestId).orElseThrow().runId());
        assertThrows(AgentStorageException.class, () -> store.create(duplicateTarget));
        assertEquals(first.runId(), store.findByDispatchRequestId(dispatchRequestId).orElseThrow().runId());
    }


    private AgentRunRecord run(String runId, String dispatchRequestId) {
        return AgentRunRecord.create(
                runId,
                "trace-" + runId,
                "conversation-m1c-" + UUID.randomUUID(),
                new AgentRequest("conversation-m1c-" + UUID.randomUUID(), "alice", "test", Map.of(
                        AgentRunStore.DISPATCH_REQUEST_METADATA_KEY, dispatchRequestId)));
    }


    private AgentStorageProperties properties() {
        AgentStorageProperties result = new AgentStorageProperties();
        result.getDatasource().setUrl(environment("AGENT_STORAGE_POSTGRES_URL", "jdbc:postgresql://localhost:5432/enterprise_agent"));
        result.getDatasource().setUsername(environment("AGENT_STORAGE_POSTGRES_USERNAME", "postgres"));
        result.getDatasource().setPassword(environment("AGENT_STORAGE_POSTGRES_PASSWORD", "1234"));
        return result;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(storage.getDatasource().getUrl(),
                storage.getDatasource().getUsername(), storage.getDatasource().getPassword());
    }

    private void execute(Connection connection, String sql, Object value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.executeUpdate();
        }
    }
}
