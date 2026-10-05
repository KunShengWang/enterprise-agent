package com.agent.platform.procurement.workflow;

import com.agent.platform.procurement.model.*;
import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import tools.jackson.databind.*;
import java.util.*;

/** Domain judgments are derived from canonical reads, never from model prose. */
public final class WorkflowRequisitionValidator {
    public record Result(ProcurementCasePatch patch,Map<String,Object> facts,List<String> blockers,List<String> missing){}
    public static Result validate(JsonNode output,ProcurementCase current,WorkflowEvidence evidence,String department,ObjectMapper json){
        evidence.require(output.path("evidenceRefs"));
        var patch=json.treeToValue(output.path("caseChanges"),ProcurementCasePatch.class);
        var merger=new ProcurementCasePatchMerger();if(patch.hasChanges())merger.validate(patch);
        var state=patch.hasChanges()?merger.merge(current==null?ProcurementCaseState.empty():current.state(),patch):current==null?ProcurementCaseState.empty():current.state();
        var missing=new ArrayList<>(state.missingFields());var blockers=new ArrayList<String>();
        if(output.path("departmentId").asText().isBlank())missing.add("department");
        else if(!department.equals(output.path("departmentId").asText()))throw new IllegalArgumentException("department outside configured business scope");
        if(!evidence.completeRequisitionScan(json))throw new IllegalStateException("canonical requisition scan unavailable");
        var item=evidence.records("get_item").stream().filter(n->state.productDescription().equals(n.path("id").asText())).findFirst();
        if(item.isEmpty() && !state.productDescription().isBlank())throw new IllegalStateException("canonical item unavailable for supplied item ID");
        else if(item.isEmpty())missing.add("productDescription");
        else {if(!item.get().path("active").asBoolean())blockers.add("ITEM_INACTIVE");if(!state.productCategory().equals(item.get().path("category").asText()))throw new IllegalArgumentException("category must match canonical item");}
        var budget=evidence.records("get_budget_status").stream().filter(n->department.equals(n.path("departmentId").asText())).findFirst();
        if(budget.isEmpty())throw new IllegalStateException("canonical budget unavailable");
        var patchMap=json.convertValue(output.path("caseChanges"),Map.class);
        if(budget.isPresent() && state.budget()!=null){
            if(!state.currency().equals(budget.get().path("currency").asText()))throw new IllegalArgumentException("budget currency mismatch");
            var available=budget.get().path("available").decimalValue();
            if(available.signum()<0)throw new IllegalArgumentException("invalid available budget");
            patchMap=new LinkedHashMap<>(patchMap);patchMap.put("budget",state.budget().min(available));
            patch=json.convertValue(patchMap,ProcurementCasePatch.class);
        }
        String subject=output.path("subjectRequisitionId").asText();
        var exact=evidence.records("get_requisition").stream().filter(n->subject.equals(n.path("id").asText())).findFirst();
        if(!subject.isBlank() && (exact.isEmpty() || !department.equals(exact.get().path("departmentId").asText())
                || !state.productDescription().equals(exact.get().path("itemId").asText()) || !Objects.equals(state.quantity(),exact.get().path("quantity").asInt())))
            throw new IllegalArgumentException("subject must match exact canonical demand");
        if(exact.isPresent() && Set.of("Cancelled","Stopped").contains(exact.get().path("status").asText()))blockers.add("REQUISITION_CLOSED");
        var requisitions=new ArrayList<>(evidence.records("list_requisitions"));requisitions.addAll(evidence.records("get_requisition"));
        var duplicates=requisitions.stream().filter(n->!subject.equals(n.path("id").asText()) && department.equals(n.path("departmentId").asText())
                && state.productDescription().equals(n.path("itemId").asText()) && Objects.equals(state.quantity(),n.path("quantity").asInt())
                && !Set.of("Cancelled","Stopped").contains(n.path("status").asText())).map(n->n.path("id").asText()).distinct().toList();
        // A single matching saved demand may be the demand currently being discussed.
        // Without a bound subject, duplication requires two distinct saved records.
        boolean duplicateConfirmed=subject.isBlank()?duplicates.size()>1:!duplicates.isEmpty();
        if(duplicateConfirmed)blockers.add("DUPLICATE_REQUEST");
        var rejected=new ArrayList<String>();
        for(var candidate:output.path("blockerCandidates")){
            evidence.require(candidate.path("evidenceRefs"));if(candidate.path("evidenceRefs").isEmpty())throw new IllegalArgumentException("candidate needs evidence");
            if(!blockers.contains(candidate.path("type").asText()))rejected.add(candidate.path("type").asText());
        }
        var facts=new LinkedHashMap<String,Object>();facts.put("authority","java-validated-domain-result");facts.put("validatedBlockers",blockers);
        facts.put("rejectedCandidateCount",rejected.size());facts.put("requiredAmountKnown",false);facts.put("subjectRequisitionId",subject);facts.put("duplicateWitnessIds",duplicateConfirmed?duplicates:List.of());
        facts.put("matchingRequisitionIds",duplicates);
        facts.put("budget",budget.map(n->Map.of("id",n.path("id").asText(),"available",n.path("available").decimalValue(),"currency",n.path("currency").asText())).orElse(Map.of()));
        facts.put("evidenceRefs",json.convertValue(output.path("evidenceRefs"),List.class));
        return new Result(patch,Map.copyOf(facts),List.copyOf(blockers),List.copyOf(missing));
    }
}
