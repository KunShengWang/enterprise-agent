package com.agent.platform.procurement;

import com.agent.platform.EnterpriseAgentApplication;
import com.agent.platform.agent.AgentRequest;
import com.agent.platform.procurement.agent.*;
import com.agent.platform.approval.*;
import com.agent.platform.config.*;
import com.agent.platform.guardrail.*;
import com.agent.platform.llm.ConfiguredLlmCostCalculator;
import com.agent.platform.mcp.*;
import com.agent.platform.memory.RuleBasedConversationSummarizer;
import com.agent.platform.procurement.application.*;
import com.agent.platform.procurement.config.*;
import com.agent.platform.procurement.erp.*;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.provider.McpProcurementDataProvider;
import com.agent.platform.procurement.tool.*;
import com.agent.platform.rag.RagService;
import com.agent.platform.runtime.*;
import com.agent.platform.skill.SkillRegistry;
import com.agent.platform.tool.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import static com.agent.platform.procurement.ProcurementLiveEvalRuntimeHarness.*;
import static org.mockito.Mockito.*;

/** Phase 6 wiring only. Frozen task, faults, approval probes and business grader remain unchanged. */
public final class ProcurementMultiAgentTrialDriver {
    private static final String TENANT = "tenant-1", USER = "buyer-1";
    private final ObjectMapper json;
    private final Path output;
    private final List<Object> events = new ArrayList<>(), models = new ArrayList<>(), mcps = new ArrayList<>(), actors = new ArrayList<>(), attempts = new ArrayList<>(), inputChecks = new ArrayList<>();
    private final Map<String,Object> controls = new LinkedHashMap<>();
    private String actor = "agent";
    private ProcurementMultiAgentTrialDriver(ObjectMapper json, Path output) { this.json=json; this.output=output; }

    public static void main(String[] args) throws Exception {
        Path taskFile=Path.of(args[0]), output=Path.of(args[1]); Files.createDirectories(output.getParent());
        try (var context=new SpringApplicationBuilder(EnterpriseAgentApplication.class).profiles("erpnext").web(WebApplicationType.NONE)
                .properties("spring.main.lazy-initialization=true", "enterprise-agent.mock-mode=false",
                        "enterprise-agent.model-tool-calling-mode=native", "enterprise-agent.workbench.web.enabled=false").run()) {
            var json=context.getBean(ObjectMapper.class);
            var task=json.readTree(Files.readString(taskFile,StandardCharsets.UTF_8));
            var driver=new ProcurementMultiAgentTrialDriver(json,output);
            var model=context.getBean(AgentModelGateway.class);
            if (!(model instanceof NativeToolCallingAgentModelGateway)) throw new IllegalStateException("real native model required");
            var properties=context.getBean(AgentProperties.class); // No limit/retry/context overrides.
            var domainRegistry=new AgentRegistry(properties);
            var profile=domainRegistry.supervisor();
            driver.controls.put("profile",profile);
            driver.controls.put("profilePromptSha256",hash(profile.systemPrompt()));
            driver.controls.put("maxToolExecutionAttempts",properties.getMaxToolExecutionAttempts());
            driver.controls.put("agentProperties",json.valueToTree(properties));
            for (String key:List.of("spring.ai.deepseek.chat.model","spring.ai.deepseek.chat.temperature","spring.ai.deepseek.chat.max-tokens"))
                driver.controls.put(key,context.getEnvironment().getProperty(key));
            var modelOptions=(org.springframework.ai.deepseek.DeepSeekChatOptions)org.springframework.test.util.ReflectionTestUtils.getField(model,"configuredOptions");
            driver.controls.put("effectiveModelOptions",Map.of("model",modelOptions.getModel(),"temperature",modelOptions.getTemperature(),"maxTokens",modelOptions.getMaxTokens()));
            driver.controls.put("reasoningSetting","not explicitly configured by Phase-1");
            var server=new McpProperties.Server(); server.setServerName("erpnext"); server.setToolNamePrefix("mcp.erpnext.");
            server.setCommand(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
            server.setArgs(List.of("-cp",System.getProperty("java.class.path"),ProcurementMcpServer.class.getName()));
            var mcpProperties=new McpProperties(); mcpProperties.setServers(List.of(server));
            var transport=new StdioMcpToolGateway(mcpProperties,json);
            try {
                var mcp=driver.observeMcp(transport,task.path("fault").asText("none"));
                var source=source(mcp);
                var data=context.getBean(ProcurementDataProperties.class);
                if (!"mcp".equals(data.getProvider()) || !data.getScenarioFile().isBlank()) throw new IllegalStateException("ERP-only provider required");
                var provider=new McpProcurementDataProvider(json,data,source);
                var cases=new MemoryCaseStore(); var timeline=new InMemoryTimelineStore(); var runs=new EvalRunStore();
                var executions=new ProcurementMultiAgentTests.DomainToolStore(); var approvalStore=new EvalApprovalStore();
                var approvals=new LocalApprovalService(approvalStore,properties);
                var merger=new ProcurementCasePatchMerger(); var engine=new ProcurementDecisionEngine();
                var handler=new ProcurementToolHandler(provider,json,cases,new ProcurementCaseService(cases,merger),
                        new ProcurementRecommendationFinalizer(cases,provider,engine),merger,engine);
                var binding=new ProcurementDomainBinding(runs,executions);
                var preparer=new ProcurementRfqApprovalPreparer(executions,cases,json);preparer.setDomainBinding(binding);
                var gateway=new ErpNextProcurementRfqGateway(mcp,json,new ErpRfqApprovalGuard(approvalStore,runs,executions,preparer,json));
                var rfq=new ProcurementRfqToolHandler(gateway,cases,executions,json);rfq.setDomainBinding(binding);
                var deferred=mock(AgentRuntime.class);
                var domains=new SubAgentExecutor(deferred,runs,executions,cases,domainRegistry,json);
                var adapter=new AgentToolAdapter(domains,cases,json,runs);
                ObjectProvider<ToolCatalogContributor> contributors=mock(ObjectProvider.class);
                when(contributors.orderedStream()).thenAnswer(i->Stream.of(new ProcurementToolCatalog(),domainRegistry));
                var registry=new LocalToolRegistry(source,contributors); var capabilities=new DefaultAgentCapabilityRegistry(registry);domains.setCapabilities(capabilities);
                ObjectProvider<ToolHandler> handlers=mock(ObjectProvider.class);
                when(handlers.orderedStream()).thenAnswer(i->Stream.of(handler,rfq,adapter));
                var local=new LocalToolExecutor(registry,new JsonSchemaToolParameterValidator(json),mock(ToolRunRecorder.class),mock(TicketStore.class),source,handlers);
                var executor=new DefaultAgentCapabilityExecutor(mock(RagService.class),local,mock(SkillRegistry.class));
                var detector=context.getBean(PromptInjectionDetector.class);
                var llmService=context.getBean(com.agent.platform.llm.LlmService.class);
                PromptInjectionDetector observedDetector=input->{
                    long began=System.nanoTime();var decision=detector.detect(input);
                    var entry=new LinkedHashMap<String,Object>();entry.put("decision",decision);entry.put("usage",llmService.lastUsage().orElse(null));
                    entry.put("latencyMs",(System.nanoTime()-began)/1_000_000.0);driver.inputChecks.add(entry);driver.append("inputCheck",entry);return decision;
                };
                var guardrail=new DefaultGuardrailService(observedDetector,context.getBean(SensitiveDataFilter.class),
                        context.getBean(ToolPermissionPolicy.class),mock(GuardrailAuditRecorder.class));
                var toolRuntime=new DefaultAgentToolRuntime(guardrail,approvals,executions,driver.observeExecutor(executor),properties,List.of(preparer),List.of(rfq));
                toolRuntime.setRunLinks(new AgentRunLinks(runs));toolRuntime.setChildBusinessScope(new ChildBusinessScope(runs,json));
                var memory=new EmptyMemoryService();
                var manager=new DefaultAgentContextManager(timeline,new ConservativeTokenEstimator(),memory,new RuleBasedConversationSummarizer(),properties,List.of(new ProcurementCaseContextRenderer(cases,json),new DomainCaseContextProvider(new ProcurementCaseContextRenderer(cases,json),new AgentRunLinks(runs))));
                var runtime=new DefaultAgentRuntime(properties,timeline,runs,executions,manager,driver.observeModel(model),capabilities,toolRuntime,guardrail,List.of(),approvals,
                        new ConservativeTokenEstimator(),new NoopRunControlStore(),memory,new ConfiguredLlmCostCalculator(properties),new ToolResultProjector(properties));
                runtime.setResultMetadataProviders(List.of(new ProcurementExecutionSummaries(runs,executions,cases,json)));
                when(deferred.run(any(),any(),any())).thenAnswer(i->runtime.run(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
                when(deferred.resume(anyString(),any())).thenAnswer(i->runtime.resume(i.getArgument(0),i.getArgument(1)));
                String session="eval-v2-"+UUID.randomUUID(); long start=System.nanoTime();
                AgentEventListener listener=e->{driver.events.add(e);driver.append("event",e);};
                AgentRuntimeResult result=runtime.run(new AgentRequest(session,USER,task.path("user_input").asText(),
                        Map.of("tenantId",TENANT,"authenticatedRoles",Set.of("USER")),profile.name()),profile,listener);
                var before=result;
                String action=task.path("approval_action").asText("none");
                if (result.state()==AgentRunState.WAITING_APPROVAL && !Set.of("none","pending").contains(action)) {
                    driver.actor="environment";
                    var requested=approvalStore.find(result.approvalId()).orElseThrow();
                    approvals.decide(requested.approvalId(),!action.equals("reject"),"eval-human",action);
                    var approved=approvalStore.find(requested.approvalId()).orElseThrow();
                    var policy=new ToolPolicyContext(approved.runId(),approved.conversationId(),USER,TENANT,Set.of("USER"),Map.of());
                    var definition=new ProcurementToolCatalog().definitions().stream().filter(d->d.name().equals(ProcurementToolCatalog.CREATE_RFQ)).findFirst().orElseThrow();
                    driver.actors.add(Map.of("action","decision","record",approved));
                    if(action.equals("stale_case")) {
                        var c=cases.findByTenantUserAndConversationId(TENANT,USER,session).orElseThrow();
                        boolean changed=cases.saveIfVersion(new ProcurementCase(c.caseId(),c.tenantId(),c.conversationId(),c.userId(),c.status(),c.state(),c.createdAt(),Instant.now(),c.version()+1,"eval-concurrent-update"),c.version());
                        if(!changed) throw new IllegalStateException("eval CAS setup failed");
                    }
                    if(action.equals("stale_approval")) {
                        approved=new ApprovalRecord(approved.approvalId(),approved.runId(),approved.conversationId(),approved.toolCallRequest(),approved.reason(),approved.status(),approved.reviewer(),approved.decisionReason(),approved.createdAt(),Instant.now().minusSeconds(1),approved.decidedAt());
                        approvalStore.save(approved);
                    }
                    if(action.startsWith("tamper_") || action.startsWith("stale_")) {
                        var probe=approved;
                        if(action.startsWith("tamper_")) {
                            var argsCopy=new LinkedHashMap<>(approved.toolCallRequest().arguments());
                            switch(action) {
                                case "tamper_supplier" -> argsCopy.put("supplierId","V2-SUP-006");
                                case "tamper_quantity" -> argsCopy.put("quantity",999);
                                case "tamper_requisition" -> argsCopy.put("requisitionId","V2-REQ-006");
                                default -> throw new IllegalArgumentException(action);
                            }
                            probe=new ApprovalRecord(approved.approvalId(),approved.runId(),approved.conversationId(),new ToolCallRequest(approved.toolCallRequest().toolName(),approved.toolCallRequest().requestId(),argsCopy),approved.reason(),approved.status(),approved.reviewer(),approved.decisionReason(),approved.createdAt(),approved.expiresAt(),approved.decidedAt());
                        }
                        var observed=toolRuntime.executeApproved(probe,definition,policy);
                        driver.actors.add(Map.of("action",action,"result",observed));
                    } else {
                        driver.actor="agent"; result=runtime.resume(result.runId(),listener);
                        if(action.equals("duplicate") || action.equals("replay")) {
                            driver.actor="environment";
                            var observed=toolRuntime.executeApproved(approved,definition,policy);
                            driver.actors.add(Map.of("action",action,"result",observed));
                            if(action.equals("replay")) {
                                var r=rfq.execute(approved.toolCallRequest(),new ToolExecutionContext(approved.runId(),session,USER,TENANT,Set.of("USER"),Map.of()));
                                driver.actors.add(Map.of("action","gateway_replay","result",r));
                            }
                        }
                    }
                }
                var out=new LinkedHashMap<String,Object>();
                out.put("result",result); out.put("beforeApproval",before); out.put("controls",driver.controls);
                out.put("case",cases.findByTenantUserAndConversationId(TENANT,USER,session).orElse(null));
                out.put("executions",executions.all()); out.put("approvals",approvalStore.recent(100));
                out.put("runs",runs.recent(100));
                out.put("events",runs.recent(100).stream().flatMap(r->timeline.loadEvents(r.runId(),10000).stream()).sorted(Comparator.comparing(AgentEvent::createdAt)).toList()); out.put("models",driver.models); out.put("mcp",driver.mcps); out.put("environmentActions",driver.actors); out.put("attempts",driver.attempts);
                out.put("taskLatencyMs",(System.nanoTime()-start)/1_000_000.0);
                out.put("inputChecks",driver.inputChecks);
                ProcurementEvaluationReports.write(output,out);
            } finally { transport.shutdown(); }
        }
    }

    private ContextualAgentCapabilityExecutor observeExecutor(ContextualAgentCapabilityExecutor delegate) {
        return new ContextualAgentCapabilityExecutor() {
            public ToolCallResult execute(ToolCallRequest request) { return execute(request,ToolExecutionContext.empty()); }
            public ToolCallResult execute(ToolCallRequest request,ToolExecutionContext context) {
                var entry=new LinkedHashMap<String,Object>(); entry.put("actor",actor); entry.put("request",request); long start=System.nanoTime();
                try { var result=delegate.execute(request,context); entry.put("result",result); return result; }
                catch(RuntimeException e) { entry.put("exception",e.getClass().getName()+": "+e.getMessage()); throw e; }
                finally { entry.put("latencyMs",(System.nanoTime()-start)/1_000_000.0); attempts.add(entry); append("attempt",entry); }
            }
        };
    }

    private AgentModelGateway observeModel(AgentModelGateway delegate) {
        return new AgentModelGateway() {
            public AgentModelTurn nextTurn(AgentModelRequest request) { return record(request,()->delegate.nextTurn(request)); }
            public AgentModelTurn nextTurn(AgentModelRequest request,AgentModelDeltaListener listener) { return record(request,()->delegate.nextTurn(request,listener)); }
            private AgentModelTurn record(AgentModelRequest request,java.util.function.Supplier<AgentModelTurn> operation) {
                var entry=new LinkedHashMap<String,Object>(); long start=System.nanoTime();
                entry.put("request",request); entry.put("effectivePromptSha256",hash(request.systemPrompt()));
                var sorted=request.tools().stream().sorted(Comparator.comparing(ToolDefinition::name)).toList();
                entry.put("schemaSha256",hash(json.writeValueAsString(sorted)));
                try { var turn=operation.get(); entry.put("turn",turn); return turn; }
                catch(RuntimeException e) { entry.put("exception",e.getClass().getName()+": "+e.getMessage()); throw e; }
                finally { entry.put("latencyMs",(System.nanoTime()-start)/1_000_000.0); models.add(entry); append("model",entry); }
            }
        };
    }

    private McpToolGateway observeMcp(McpToolGateway delegate,String fault) {
        return new McpToolGateway() {
            private int matching;
            public List<ToolDefinition> discoverTools() { return delegate.discoverTools(); }
            public List<ToolDefinition> refreshTools() { return delegate.refreshTools(); }
            public ToolCallResult callTool(ToolCallRequest request) { return callTool(null,request); }
            public ToolCallResult callTool(ToolDefinition definition,ToolCallRequest request) {
                long start=System.nanoTime(); var entry=new LinkedHashMap<String,Object>();
                var loggedArguments=new LinkedHashMap<>(request.arguments());
                if(loggedArguments.containsKey("signature")) loggedArguments.put("signature","[REDACTED ephemeral write capability]");
                entry.put("actor",actor); entry.put("request",new ToolCallRequest(request.toolName(),request.requestId(),loggedArguments)); entry.put("startedAt",Instant.now()); boolean read=!request.toolName().contains("create_rfq");
                boolean inject=read && !fault.equals("none") && (++matching==1 || fault.equals("permanent"));
                entry.put("injected",inject);
                try {
                    ToolCallResult result;
                    if(inject && Set.of("transient","permanent").contains(fault)) result=new ToolCallResult(request.toolName(),false,"{\"error\":{\"code\":\"ERP_UNAVAILABLE\"}}","injected MCP 503",Map.of("errorType","ERP_UNAVAILABLE"));
                    else if(inject && fault.equals("malformed")) result=new ToolCallResult(request.toolName(),true,"{\"records\":\"malformed\"}","",Map.of());
                    else {
                        result=definition==null?delegate.callTool(request):delegate.callTool(definition,request);
                        if(inject && fault.equals("large") && result.success()) {
                            var node=json.readTree(result.content());
                            if(node.isObject()) ((tools.jackson.databind.node.ObjectNode)node).put("diagnosticPadding","ERP observation. ".repeat(6000));
                            result=new ToolCallResult(result.toolName(),result.success(),json.writeValueAsString(node),result.errorMessage(),result.metadata());
                        }
                    }
                    entry.put("result",result); return result;
                } catch(RuntimeException e) { entry.put("exception",e.getClass().getName()+": "+e.getMessage()); throw e; }
                finally { entry.put("latencyMs",(System.nanoTime()-start)/1_000_000.0); mcps.add(entry); append("mcp",entry); }
            }
        };
    }
    private void append(String type,Object record) {
        try { Files.writeString(Path.of(output+".trace.jsonl"),json.writeValueAsString(Map.of("type",type,"data",record))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> source(T value) { var p=mock(ObjectProvider.class); when(p.getIfAvailable()).thenReturn(value); return p; }

    /** Test storage only. Same conditional resume transitions as the existing persisted RunStore. */
    static final class EvalRunStore implements AgentRunStore {
        private final Map<String,AgentRunRecord> values=new LinkedHashMap<>();
        public synchronized AgentRunRecord create(AgentRunRecord r) { values.put(r.runId(),r); return r; }
        public synchronized Optional<AgentRunRecord> find(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized List<AgentRunRecord> recent(int limit) { return values.values().stream().limit(limit).toList(); }
        public synchronized AgentRunRecord update(String id,UnaryOperator<AgentRunRecord> f) { return values.compute(id,(k,v)->f.apply(v)); }
        public synchronized Optional<AgentRunRecord> claimForResume(String id) {
            var r=values.get(id); return r!=null && r.state()==AgentRunState.WAITING_APPROVAL ? Optional.of(update(id,AgentRunRecord::claimedForResume)):Optional.empty();
        }
        public synchronized Optional<AgentRunRecord> claimPausedForResume(String id) {
            var r=values.get(id); return r!=null && Set.of(AgentRunState.PAUSED,AgentRunState.PAUSE_REQUESTED).contains(r.state()) ? Optional.of(update(id,AgentRunRecord::claimedPausedForResume)):Optional.empty();
        }
    }
    static final class EvalApprovalStore implements ApprovalStore {
        private final Map<String,ApprovalRecord> values=new LinkedHashMap<>();
        public synchronized void save(ApprovalRecord r) { values.put(r.approvalId(),r); }
        public synchronized Optional<ApprovalRecord> find(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized List<ApprovalRecord> recent(int n) { return values.values().stream().limit(n).toList(); }
        public synchronized boolean decideIfRequestedAndNotExpired(String id,ApprovalRecord next,Instant now) {
            var r=values.get(id); if(r==null || r.status()!=ApprovalStatus.REQUESTED || !now.isBefore(r.expiresAt())) return false;
            save(next); return true;
        }
        public synchronized boolean expireIfRequested(String id,ApprovalRecord next,Instant now) {
            var r=values.get(id); if(r==null || r.status()!=ApprovalStatus.REQUESTED || now.isBefore(r.expiresAt())) return false;
            save(next); return true;
        }
    }
}
