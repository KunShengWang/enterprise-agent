package com.agent.platform.workbench.security;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcConversationOwnershipStoreTests {
    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal("tenant", "alice", Set.of("USER"));
    private final Connection connection = mock(Connection.class);
    private int inserted = 1;
    private String ownerTenant = "tenant";
    private List<String[]> workbench = List.of();
    private List<String[]> cases = List.of();
    private List<String[]> sessions = List.of();
    private final JdbcConversationOwnershipStore store = new JdbcConversationOwnershipStore(new AgentStorageProperties()) {
        @Override protected Connection open() { return connection; }
    };

    JdbcConversationOwnershipStoreTests() throws Exception {
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            var statement = mock(PreparedStatement.class);
            if (sql.contains("INSERT INTO agent_public")) when(statement.executeUpdate()).thenAnswer(c -> inserted);
            else {
                List<String[]> rows;
                if (sql.contains("to_regclass")) rows = Collections.singletonList(new String[]{"table"});
                else if (sql.contains("FROM agent_public")) rows = Collections.singletonList(new String[]{ownerTenant,"alice"});
                else if (sql.contains("FROM agent_conversation_work_state")) rows = workbench;
                else if (sql.contains("FROM procurement_case_state")) rows = cases;
                else if (sql.contains("FROM agent_session")) rows = sessions;
                else throw new AssertionError(sql);
                var result = mock(ResultSet.class);
                int[] index = {-1};
                when(result.next()).thenAnswer(c -> ++index[0] < rows.size());
                when(result.getString(anyInt())).thenAnswer(c -> rows.get(index[0])[(int)c.getArgument(0)-1]);
                when(statement.executeQuery()).thenReturn(result);
            }
            return statement;
        });
    }

    @Test void newConversationCommitsOwnerButUnknownReadRollsBack() throws Exception {
        assertFalse(store.check(principal, "unknown", false)); verify(connection).rollback();
        assertTrue(store.check(principal, "new", true)); verify(connection).commit();
    }
    @Test void existingOwnerSurvivesRestartWithoutBusinessRows() throws Exception {
        inserted=0;
        assertTrue(store.check(principal, "owned", false)); verify(connection).commit();
    }
    @Test void concurrentInsertWinnerFromAnotherTenantIsRejected() throws Exception {
        inserted=0; ownerTenant="foreign";
        assertThrows(WorkbenchAccessDeniedException.class, () -> store.check(principal,"shared",true));
        verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void uniqueLegacyWorkbenchOwnershipKeepsOriginalTimelineId() throws Exception {
        workbench=Collections.singletonList(new String[]{"tenant","alice"});
        sessions=Collections.singletonList(new String[]{"alice"});
        assertTrue(store.check(principal,"legacy",true)); verify(connection).commit();
    }
    @Test void legacyDirectCaseProvesTenantForOriginalSession() throws Exception {
        cases=Collections.singletonList(new String[]{"tenant","alice"});
        sessions=Collections.singletonList(new String[]{"alice"});
        assertTrue(store.check(principal,"legacy-direct",false)); verify(connection).commit();
    }
    @Test void legacyUserAloneCannotProveTenantOwnership() throws Exception {
        sessions=Collections.singletonList(new String[]{"alice"});
        assertThrows(WorkbenchAccessDeniedException.class, () -> store.check(principal,"legacy",true));
        verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void multipleLegacyOwnersFailClosedEvenWhenOneMatches() throws Exception {
        workbench=List.of(new String[]{"tenant","alice"}, new String[]{"other","alice"});
        assertThrows(WorkbenchAccessDeniedException.class, () -> store.check(principal,"shared",true));
        verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void conflictingTimelineUserFailsClosedDespiteMatchingCase() throws Exception {
        cases=Collections.singletonList(new String[]{"tenant","alice"});
        sessions=Collections.singletonList(new String[]{"bob"});
        assertThrows(WorkbenchAccessDeniedException.class, () -> store.check(principal,"shared",true));
        verify(connection).rollback(); verify(connection,never()).commit();
    }
}
