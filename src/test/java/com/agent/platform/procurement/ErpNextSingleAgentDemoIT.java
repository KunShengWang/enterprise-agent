package com.agent.platform.procurement;

import com.agent.platform.EnterpriseAgentApplication;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.approval.ApprovalService;
import com.agent.platform.config.*;
import com.agent.platform.guardrail.*;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.mcp.*;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.*;
import com.agent.platform.procurement.provider.McpProcurementDataProvider;
import com.agent.platform.procurement.erp.ProcurementMcpServer;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.*;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static com.agent.platform.procurement.ProcurementLiveEvalRuntimeHarness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** One real-model, real-ERP smoke demo. No grader, benchmark, or new Runtime implementation. */
@EnabledIfEnvironmentVariable(named="ERPNEXT_AGENT_DEMO",matches="true")
class ErpNextSingleAgentDemoIT {
    @Test void sameSingleAgentReadsErpAndFinalizesEvidenceBackedRecommendation() throws Exception {
        assertNotNull(System.getenv("ERPNEXT_API_KEY"));assertNotNull(System.getenv("DEEPSEEK_API_KEY"));
        try(var context=new SpringApplicationBuilder(EnterpriseAgentApplication.class).profiles("erpnext").web(WebApplicationType.NONE)
                .properties("spring.main.lazy-initialization=true","enterprise-agent.mock-mode=false",
                        "enterprise-agent.model-tool-calling-mode=native","enterprise-agent.workbench.web.enabled=false").run()){
            var json=context.getBean(ObjectMapper.class);var model=context.getBean(AgentModelGateway.class);
            assertInstanceOf(NativeToolCallingAgentModelGateway.class,model);
            var server=new McpProperties.Server();server.setServerName("erpnext");server.setToolNamePrefix("mcp.erpnext.");
            server.setCommand(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
            server.setArgs(List.of("-cp",System.getProperty("java.class.path"),ProcurementMcpServer.class.getName()));
            var mcpProperties=new McpProperties();mcpProperties.setServers(List.of(server));
            var mcp=new StdioMcpToolGateway(mcpProperties,json);
            try {
                var data=context.getBean(ProcurementDataProperties.class);
                assertEquals("mcp",data.getProvider());assertEquals("",data.getScenarioFile());
                ObjectProvider<McpToolGateway> mcpSource=source(mcp);
                var provider=new McpProcurementDataProvider(json,data,mcpSource);
                var cases=new MemoryCaseStore();var timeline=new InMemoryTimelineStore();var runs=new InMemoryRunStore();
                var executions=new InMemoryToolExecutionStore();var merger=new ProcurementCasePatchMerger();var engine=new ProcurementDecisionEngine();
                var handler=new ProcurementToolHandler(provider,json,cases,new ProcurementCaseService(cases,merger),
                        new ProcurementRecommendationFinalizer(cases,provider,engine),merger,engine);
                ObjectProvider<ToolCatalogContributor> contributors=mock(ObjectProvider.class);
                when(contributors.orderedStream()).thenAnswer(i->Stream.of(new ProcurementToolCatalog()));
                var registry=new LocalToolRegistry(mcpSource,contributors);var capabilities=new DefaultAgentCapabilityRegistry(registry);
                ObjectProvider<ToolHandler> handlers=mock(ObjectProvider.class);when(handlers.orderedStream()).thenAnswer(i->Stream.of(handler));
                var local=new LocalToolExecutor(registry,new JsonSchemaToolParameterValidator(json),mock(ToolRunRecorder.class),mock(TicketStore.class),mcpSource,handlers);
                var executor=new DefaultAgentCapabilityExecutor(mock(RagService.class),local,mock(SkillRegistry.class));
                var properties=context.getBean(AgentProperties.class);properties.setMaxToolExecutionAttempts(1);
                var guardrail=mock(GuardrailService.class);
                when(guardrail.checkInput(anyString())).thenReturn(GuardrailDecision.allow(GuardrailStage.INPUT,"demo"));
                when(guardrail.checkOutput(anyString())).thenReturn(GuardrailDecision.allow(GuardrailStage.OUTPUT,"demo"));
                when(guardrail.checkToolCall(any(),any(),any())).thenAnswer(i->{ToolDefinition d=i.getArgument(0);
                    return d.name().contains("create_rfq")?GuardrailDecision.block(GuardrailStage.TOOL,"read-only demo"):
                            GuardrailDecision.allow(GuardrailStage.TOOL,"demo read");});
                var approvals=mock(ApprovalService.class);var memory=new EmptyMemoryService();
                var toolRuntime=new DefaultAgentToolRuntime(guardrail,approvals,executions,executor,properties,List.of(),List.of());
                var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),memory,new RuleBasedConversationSummarizer(),properties,List.of(new ProcurementCaseContextRenderer(cases,json)));
                var factory=context.getBean(ProcurementSourcingExecutionProfileFactory.class);
                var profile=factory.createProfile();
                var runtime=new DefaultAgentRuntime(properties,timeline,runs,executions,manager,model,capabilities,toolRuntime,guardrail,List.of(),approvals,
                        new ConservativeTokenEstimator(),new NoopRunControlStore(),memory,new ConfiguredLlmCostCalculator(properties),new ToolResultProjector(properties));
                var result=runtime.run(new AgentRequest("erp-demo-"+UUID.randomUUID(),"buyer-1",
                        "算法部门 V2-ALG 计划采购 20 台 AI 工作站，预算 30 万人民币，21 天内交付，GPU 显存至少 24GB。请查询 ERPNext 的商品、部门预算、供应关系、供应商、采购历史和绩效，推荐历史交付稳定的供应商并解释权衡。本次只做推荐，不创建 RFQ。",
                        Map.of("tenantId","tenant-1","authenticatedRoles",Set.of("USER")),profile.name()),profile,AgentEventListener.NOOP);
                Files.createDirectories(Path.of("target/erpnext"));
                Files.writeString(Path.of("target/erpnext/agent-demo.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "dataSource","ERPNext","modelGateway",model.getClass().getSimpleName(),"result",result,"tools",executions.all())));
                assertEquals(AgentRunState.COMPLETED,result.state(),result.answer());
                assertTrue(executions.all().stream().anyMatch(t->t.toolName().equals(ProcurementToolCatalog.RECOMMENDATION_FINALIZE)&&t.state()==ToolExecutionState.SUCCEEDED));
                for(String tool:List.of("procurement_get_budget_status","procurement_list_item_suppliers","procurement_get_purchase_history","procurement_get_supplier_performance"))
                    assertTrue(executions.all().stream().anyMatch(t->t.toolName().equals("mcp.erpnext."+tool)&&t.state()==ToolExecutionState.SUCCEEDED),tool);
                assertTrue(executions.all().stream().anyMatch(t->Set.of("mcp.erpnext.procurement_get_supplier","mcp.erpnext.procurement_list_suppliers")
                        .contains(t.toolName())&&t.state()==ToolExecutionState.SUCCEEDED),"supplier facts via get or list");
                assertTrue(executions.all().stream().noneMatch(t->t.toolName().contains("analysis")||t.toolName().contains("create_rfq")));
            } finally {mcp.shutdown();}
        }
    }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> source(T value){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
}
