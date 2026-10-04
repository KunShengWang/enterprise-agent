package com.agent.platform.procurement.agent;

import com.agent.platform.procurement.erp.ProcurementErpAdapter.*;
import com.agent.platform.procurement.model.*;
import com.agent.platform.runtime.*;
import tools.jackson.databind.*;
import java.util.*;
import java.math.BigDecimal;

/** Candidate judgments are untrusted; only receipt-backed rules produce domain blockers. */
public final class RequisitionAssessment {
    private RequisitionAssessment() { }
    public static final Set<String> BLOCKERS=Set.of("ITEM_INACTIVE","REQUISITION_CLOSED","BUDGET_UNAVAILABLE","BUDGET_INSUFFICIENT","DUPLICATE_REQUEST");
    public static final Set<String> DEFERRED=Set.of("DELIVERY_FEASIBILITY","SUPPLIER_PERFORMANCE","SUPPLIER_PRICING");
    public record Validation(Map<String,Object> assessment, Map<String,Object> audit) {}

    public static Map<String,Object> validate(JsonNode answer, ProcurementCaseState state,
                                              List<ToolExecutionRecord> reads,ObjectMapper json) {
        return validateInternal(answer,state,null,reads,json).assessment();
    }
    public static Validation assess(JsonNode answer, ProcurementCase current,List<ToolExecutionRecord> reads,ObjectMapper json){
        return validateInternal(answer,current==null?null:current.state(),current,reads,json);
    }
    private static Validation validateInternal(JsonNode answer,ProcurementCaseState state,ProcurementCase current,
                                               List<ToolExecutionRecord> reads,ObjectMapper json){
        var deferred=enumArray(answer.path("deferredChecks"),DEFERRED);
        if(!answer.path("subjectRequisitionId").isTextual() || !answer.path("departmentId").isTextual())
            throw new IllegalArgumentException("requisition subject and department required");
        String subject=answer.path("subjectRequisitionId").asText(), department=answer.path("departmentId").asText();
        var ledger=ChildEvidenceLedger.derive(reads,json);
        var allowed=new HashSet<String>();ledger.forEach(e->allowed.add(e.evidenceRef()));
        var candidates=answer.path("blockerCandidates");
        if(!candidates.isArray() || candidates.size()>BLOCKERS.size())throw new IllegalArgumentException("candidate array required");
        var types=new HashSet<String>();
        for(var candidate:candidates){
            var keys=new HashSet<String>();candidate.properties().forEach(e->keys.add(e.getKey()));
            if(!keys.equals(Set.of("type","evidenceRefs")) || !candidate.path("type").isTextual()
                    || !BLOCKERS.contains(candidate.path("type").asText()) || !types.add(candidate.path("type").asText())
                    || !candidate.path("evidenceRefs").isArray() || candidate.path("evidenceRefs").isEmpty()
                    || candidate.path("evidenceRefs").size()>40)throw new IllegalArgumentException("invalid blocker candidate schema");
            for(var ref:candidate.path("evidenceRefs"))
                if(!ref.isTextual() || !allowed.contains(ref.asText()))throw new IllegalArgumentException("candidate evidence outside successful child ledger");
        }
        var items=new ArrayList<Item>();var budgets=new ArrayList<BudgetStatus>();
        var requisitions=new LinkedHashMap<String,Requisition>();var exactReads=new HashSet<String>();
        var canonicalReads=new LinkedHashMap<String,ChildEvidenceLedger.Entry>();
        ledger.forEach(e->canonicalReads.putIfAbsent(e.sourceToolCallId(),e));
        boolean requisitionsRead=false, budgetMissing=false, budgetError=false;
        var missingWitnesses=new ArrayList<Map<String,Object>>();
        // NOT_FOUND is an explicit negative business observation, never a positive SUCCESS evidence ref.
        for(var e:reads)if(e.toolName().equals("mcp.erpnext.procurement_get_budget_status")
                && department.equals(e.request().arguments().get("departmentId")) && e.result()!=null && !e.result().success()){
            String code="";try{code=json.readTree(e.result().content()).path("error").path("code").asText();}catch(RuntimeException ignored){}
            if("ERP_NOT_FOUND".equals(code)){
                budgetMissing=true;missingWitnesses.add(Map.of("receiptRef","tool-execution:"+e.toolCallId(),"kind","CANONICAL_ABSENCE","outcome",code));
            }else budgetError=true;
        }
        for(var entry:canonicalReads.values()){
            String tool=entry.toolName();var data=entry.canonicalFacts();
            if(tool.equals("mcp.erpnext.procurement_get_item") && data.path("record").isObject())items.add(json.treeToValue(data.path("record"),Item.class));
            if(tool.equals("mcp.erpnext.procurement_get_budget_status") && data.path("record").isObject())budgets.add(json.treeToValue(data.path("record"),BudgetStatus.class));
            if(tool.equals("mcp.erpnext.procurement_get_requisition") && data.path("record").isObject()){
                var r=json.treeToValue(data.path("record"),Requisition.class);requisitions.put(r.id(),r);exactReads.add(r.id());
            }
            if(tool.equals("mcp.erpnext.procurement_list_requisitions")){
                requisitionsRead=true;for(var n:data.path("records")){var r=json.treeToValue(n,Requisition.class);requisitions.put(r.id(),r);}
            }
        }
        if(!subject.isBlank() && (!exactReads.contains(subject) || !department.equals(requisitions.get(subject).departmentId())))
            throw new IllegalArgumentException("subject requires exact canonical requisition read");
        // A canonical current requisition can supply identity before a Case exists. A missing Case is not a verdict.
        Requisition subjectFact=subject.isBlank()?null:requisitions.get(subject);
        String item=state!=null?state.productDescription():subjectFact==null?null:subjectFact.itemId();
        Integer quantity=state!=null?state.quantity():subjectFact==null?null:subjectFact.quantity();
        boolean demandKnown=item!=null && !item.isBlank() && quantity!=null && quantity>0;
        var matching=requisitions.values().stream().filter(r->demandKnown && item.equals(r.itemId()) && quantity.equals(r.quantity())
                && department.equals(r.departmentId()) && !subject.equals(r.id()) && !Set.of("Cancelled","Stopped").contains(r.status())).toList();
        var targetBudgets=budgets.stream().filter(b->department.equals(b.departmentId())).toList();
        if(!targetBudgets.isEmpty())budgetMissing=false;
        var targetItems=items.stream().filter(i->item==null || item.equals(i.id())).toList();
        boolean inactive=targetItems.stream().anyMatch(i->!i.active());
        boolean closed=subjectFact!=null && Set.of("Cancelled","Stopped").contains(subjectFact.status());
        // Budget ceiling is NOT a required amount. Only a current, canonical finalized offer supplies one.
        BigDecimal required=null;String requiredCurrency="",requiredRef="";
        if(current!=null)for(var entry:canonicalReads.values())if(entry.toolName().equals("procurement_recommendation_finalize")){
            var n=entry.canonicalFacts();var offer=n.path("recommendation").path("selectedOffer");
            if(current.caseId().equals(n.path("caseId").asText()) && current.version()==n.path("caseVersion").asLong(-1)
                    && Objects.equals(item,offer.path("productId").asText()) && quantity!=null && quantity==offer.path("quantity").asInt()
                    && offer.path("unitPrice").isNumber() && offer.path("unitPrice").decimalValue().signum()>=0){
                required=offer.path("unitPrice").decimalValue().multiply(BigDecimal.valueOf(quantity));
                requiredCurrency=offer.path("currency").asText();requiredRef="tool-execution:"+entry.sourceToolCallId();
            }
        }
        boolean insufficient=false;
        for(var b:targetBudgets)if(required!=null && b.currency().equals(requiredCurrency) && b.available().compareTo(required)<0)insufficient=true;
        var supported=new LinkedHashMap<String,Boolean>();
        supported.put("ITEM_INACTIVE",inactive);supported.put("REQUISITION_CLOSED",closed);
        supported.put("BUDGET_UNAVAILABLE",budgetMissing);supported.put("BUDGET_INSUFFICIENT",insufficient);supported.put("DUPLICATE_REQUEST",!matching.isEmpty());
        var witness=new LinkedHashMap<String,Object>();
        witness.put("budgetRecords",targetBudgets);witness.put("budgetAbsence",missingWitnesses);
        witness.put("requiredAmountKnown",required!=null);if(required!=null){witness.put("requiredAmount",required);witness.put("requiredCurrency",requiredCurrency);witness.put("requiredAmountRef",requiredRef);}
        witness.put("duplicateIdentityKnown",demandKnown);witness.put("duplicateWitnesses",matching);witness.put("subjectRequisitionId",subject);
        witness.put("itemFacts",targetItems);witness.put("casePresent",state!=null);
        var validated=new LinkedHashSet<String>();supported.forEach((k,v)->{if(v)validated.add(k);});
        var rejected=new ArrayList<Map<String,Object>>();
        for(var candidate:candidates)if(!supported.get(candidate.path("type").asText()))rejected.add(Map.of(
                "type",candidate.path("type").asText(),"evidenceRefs",json.treeToValue(candidate.path("evidenceRefs"),List.class),
                "reason","CANONICAL_FACTS_DO_NOT_SUPPORT_CANDIDATE","canonicalWitness",witness));
        boolean complete=state!=null && state.missingFields().isEmpty();
        String status=!validated.isEmpty()?"BLOCKED":complete && !budgetError?"READY":"NEEDS_CLARIFICATION";
        var result=new LinkedHashMap<String,Object>();
        result.put("authority","runtime-validated-domain-result");result.put("status",status);
        result.put("validatedBlockers",validated);result.put("blockingReasons",validated);result.put("deferredChecks",deferred);
        result.put("rejectedCandidateCount",rejected.size());result.put("validationWarnings",rejected.isEmpty()?List.of():List.of("Unsupported model candidates were rejected; only validatedBlockers are authoritative."));
        result.put("subjectRequisitionId",subject);result.put("departmentId",department);result.put("caseComplete",complete);
        result.put("itemStatus",targetItems.isEmpty()?"UNKNOWN":inactive?"INACTIVE":"ACTIVE");
        result.put("budgetStatus",budgetMissing?"UNAVAILABLE":insufficient?"INSUFFICIENT":!targetBudgets.isEmpty()?"AVAILABLE_CEILING_OBSERVED":budgetError?"BACKEND_ERROR":"UNKNOWN");
        result.put("budgetFacts",targetBudgets.stream().map(b->Map.of("id",b.id(),"available",b.available(),"currency",b.currency())).toList());
        result.put("requiredAmountKnown",required!=null);if(required!=null)result.put("requiredAmount",required);
        result.put("duplicateStatus",!matching.isEmpty()?"MATCHING_REQUESTS_OBSERVED":!demandKnown?"UNKNOWN":requisitionsRead?"NO_EXACT_MATCH_OBSERVED":"NOT_CHECKED");
        result.put("duplicateWitnessIds",matching.stream().map(Requisition::id).toList());
        return new Validation(result,Map.of("blockerCandidates",json.treeToValue(candidates,List.class),"validatedBlockers",validated,
                "rejectedCandidates",rejected,"canonicalWitness",witness,"llmProposedStatus",answer.path("status").asText(),"authoritativeStatus",status));
    }
    private static Set<String> enumArray(JsonNode node,Set<String> allowed){
        if(!node.isArray() || node.size()>allowed.size())throw new IllegalArgumentException("domain enum array required");
        var values=new LinkedHashSet<String>();for(var value:node)if(!value.isTextual() || !allowed.contains(value.asText()) || !values.add(value.asText()))throw new IllegalArgumentException("invalid domain responsibility");
        return values;
    }
}
