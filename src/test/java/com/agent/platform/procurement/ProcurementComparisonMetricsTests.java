package com.agent.platform.procurement;

import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.ProcurementComparisonMetrics.*;

/** End-to-end synthetic executions, not measurements of real model capability. */
class ProcurementComparisonMetricsTests {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    static final Path FIXTURE = Path.of("data/procurement/scenarios/complex_workstation_01.json").toAbsolutePath();
    static final Path POLICY = Path.of("src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json").toAbsolutePath();
    static final List<String> ANSWERS = List.of(
            "推荐 Supplier D，总价 58 万元，交期 12 天。D 比 B 快 6 天。D 的总价满足本次预算。",
            "推荐 Supplier B，总价 55 万元，交期 18 天。B 比 D 便宜 3 万元。B 的交期满足本次交付期限。",
            "推荐 Supplier D，总价 58 万元，交期 12 天。当前原始硬约束下仅 D 合格。",
            "当前原始硬约束下无合格供应商。");
    int sequence;

    @Test void identicalResultsHaveZeroDifferencesAndEveryCountHasSources() throws Exception {
        var m = manifest(4); var r = analyze(m); assertEquals(State.READY, r.state());
        assertEquals(8, r.recordEvidence().size());
        assertTrue(r.limitations().contains("INDEPENDENCE_NOT_ATTESTED"));
        for (var d : r.differences()) { assertEquals(0, d.countDelta()); if (d.proportionDelta()!=null) assertEquals(0, d.proportionDelta().signum()); }
        for (var c : r.cases()) for (var v : c.metrics()) {
            assertEquals(v.denominator(), v.buckets().stream().mapToLong(Bucket::count).sum());
            assertEquals(1, c.expectedRecords()); assertEquals(1, c.validRecords());
            for (var b : v.buckets()) for (var s : b.sources()) {
                assertTrue(r.recordEvidence().containsKey(s.recordId()));
                assertEquals(c.caseId(), r.recordEvidence().get(s.recordId()).inputBinding().caseId());
                assertFalse(s.ref().isBlank());
            }
        }
        var before = snapshot(); analyze(m); var after = snapshot();
        assertEquals(before.keySet(), after.keySet()); for(var p : before.keySet()) assertArrayEquals(before.get(p), after.get(p));
    }

    @ParameterizedTest @ValueSource(strings={"total","direction","difference","extra"})
    void rawFactMutationsChangeTheirOwnMetrics(String mutation) throws Exception {
        var m=manifest(1); String answer=ANSWERS.get(0); Key key=Key.SCALAR_FACT;
        switch(mutation) {
            case "total" -> answer=answer.replace("58 万元","57 万元");
            case "direction" -> { answer=answer.replace("快 6 天","慢 6 天"); key=Key.RELATION_DIRECTION; }
            case "difference" -> { answer=answer.replace("快 6 天","快 5 天"); key=Key.RELATION_DIFFERENCE; }
            default -> answer += "Supplier B 总价 99 万元。";
        }
        replace(m,"B",0,answer,n->{}); var r=analyze(m);
        assertEquals(State.READY,r.state()); assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,"FAIL"));
        assertEquals(0,count(r,"A",0,key,"FAIL")); assertTrue(count(r,"B",0,key,"FAIL")>0);
        assertTrue(r.differences().stream().anyMatch(d->d.status().equals("FAIL")&&d.countDelta()>0));
    }

    @ParameterizedTest @ValueSource(strings={"delivery_advantage","budget_compliance"})
    void missingElementsExplainDifferencesByActualPolicyId(String element) throws Exception {
        var m=manifest(1); String remove=element.equals("delivery_advantage")?"D 比 B 快 6 天。":"D 的总价满足本次预算。";
        replace(m,"B",0,ANSWERS.get(0).replace(remove,""),n->{}); var r=analyze(m);
        var missing=bucket(metric(r,"B",0,Key.REQUIRED_ELEMENTS),"MISSING");
        assertEquals(1,missing.count()); assertEquals("element:"+element,missing.sources().get(0).ref());
        assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,"FAIL"));
    }

    @Test void evidenceLossDoesNotChangeTrueDirectionIntoFalseFact() throws Exception {
        var m=manifest(1); replace(m,"B",0,ANSWERS.get(0),n->payload(n,p->{
            var offers=(ArrayNode)p.path("offers"); for(int i=offers.size()-1;i>=0;i--) if(offers.get(i).path("supplierId").asText().equals("supplier-b")) offers.remove(i);
        })); var r=analyze(m);
        assertEquals(count(r,"A",0,Key.RELATION_DIRECTION,"PASS"),count(r,"B",0,Key.RELATION_DIRECTION,"PASS"));
        assertTrue(count(r,"B",0,Key.RELATION_DIRECTION_EVIDENCE,"SKIP")>0);
        assertTrue(count(r,"B",0,Key.RELATION_VERIFIED,"SKIP")>0);
        assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,"NEEDS_REVIEW"));
        assertTrue(metric(r,"B",0,Key.RELATION_DIRECTION_EVIDENCE).buckets().stream().flatMap(b->b.sources().stream()).anyMatch(s->!s.reason().isBlank()));
    }

    @Test void unknownContentChangesCoverageAndRetainsBlockers() throws Exception {
        var m=manifest(1); replace(m,"B",0,ANSWERS.get(0)+"保证绝不延期。",n->{}); var r=analyze(m);
        assertTrue(count(r,"B",0,Key.EFFECTIVE_COVERAGE,"UNKNOWN_CONTENT")+count(r,"B",0,Key.EFFECTIVE_COVERAGE,"UNRESOLVED_BUSINESS")>0);
        assertTrue(count(r,"B",0,Key.ASSESSMENT_COVERAGE,"BLOCKED")>0);
        assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,"NEEDS_REVIEW"));
        assertFalse(bucket(metric(r,"B",0,Key.ASSESSMENT_COVERAGE),"BLOCKED").sources().get(0).evidencePaths().isEmpty());
    }

    @Test void missingClaimsAndInapplicableDifferenceHaveNoInventedPerfectScore() throws Exception {
        var m=manifest(1); replace(m,"B",0,"",n->{}); var r=analyze(m);
        // Both existing extractors retain an unresolved empty-answer diagnostic.
        assertEquals(0,count(r,"B",0,Key.SCALAR_FACT,"PASS"));
        assertEquals(0,count(r,"B",0,Key.RELATION_DIRECTION,"PASS"));
        replace(m,"B",0,ANSWERS.get(0).replace("快 6 天","快"),n->{}); r=analyze(m);
        var v=metric(r,"B",0,Key.RELATION_DIFFERENCE); assertEquals(0,v.denominator());
        assertNull(v.successRatio().value()); assertEquals(Calculation.NOT_COMPUTABLE,v.successRatio().calculation());
        assertTrue(v.exclusions().stream().anyMatch(e->e.reason().startsWith("NOT_APPLICABLE:")));
        assertTrue(v.exclusions().stream().allMatch(e->e.reason().startsWith("NOT_APPLICABLE:")||e.reason().equals("VERIFIED_DUPLICATE_HANDOFF")));
    }

    @Test void removingExecutionEvidenceChangesScalarSupportWithoutInventingFactFailure() throws Exception {
        var m=manifest(1); replace(m,"B",0,ANSWERS.get(0),n->n.set("toolExecutions",json.createArrayNode())); var r=analyze(m);
        assertEquals(State.READY,r.state()); assertTrue(count(r,"B",0,Key.SCALAR_EVIDENCE,"SKIP")>0);
        assertEquals(0,count(r,"B",0,Key.SCALAR_FACT,"FAIL")); assertEquals(0,count(r,"B",0,Key.SCALAR_VERIFIED,"PASS"));
        assertTrue(count(r,"A",0,Key.SCALAR_VERIFIED,"PASS")>0);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void scoringErrorIsNotCountedAsFactFailure(boolean wrongFact) throws Exception {
        var m=manifest(1); String answer=wrongFact?ANSWERS.get(0).replace("58 万元","57 万元"):ANSWERS.get(0);
        replace(m,"B",0,answer,n->payload(n,p->p.put("offers",42))); var r=analyze(m);
        assertEquals(State.READY,r.state()); assertEquals(1,count(r,"B",0,Key.SCORING_EXECUTION,"ERROR"));
        assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,wrongFact?"FAIL":"NEEDS_REVIEW"));
        assertEquals(wrongFact?1:0,count(r,"B",0,Key.SCALAR_FACT,"FAIL"));
        assertTrue(r.recordEvidence().values().stream().anyMatch(v->v.findings().stream().anyMatch(f->f.kind()==ProcurementAnswerEvaluationV3.FindingKind.ERROR)));
    }

    @Test void macroAndMicroUseDifferentExplicitDenominators() throws Exception {
        var m=manifest(4); replace(m,"B",0,ANSWERS.get(0).replace("D 比 B 快 6 天。",""),n->{}); var r=analyze(m);
        var g=r.groupMetrics().stream().filter(x->x.groupId().equals("B")&&x.metric()==Key.REQUIRED_ELEMENTS).findFirst().orElseThrow();
        assertEquals(15,g.micro().denominator()); assertEquals(14,g.micro().successRatio().numerator());
        assertEquals(0,new BigDecimal("0.933333333333").compareTo(g.micro().successRatio().value()));
        assertEquals(0,new BigDecimal("0.95").compareTo(g.macro().value()));
        var scalar=r.groupMetrics().stream().filter(x->x.groupId().equals("B")&&x.metric()==Key.SCALAR_FACT).findFirst().orElseThrow();
        assertEquals(Calculation.NOT_COMPUTABLE,scalar.macro().calculation()); assertFalse(scalar.macro().uncomputableCases().isEmpty());
    }

    @Test void mixedTrustedStatesAndExecutionErrorsHaveSeparateDenominators() throws Exception {
        var m=manifest(1); ((ObjectNode)m.path("requiredRecordsPerCase")).put(caseId(0),4); var entries=(ArrayNode)m.path("records"); entries.removeAll();
        for(String g:List.of("A","B")) {
            entries.add(entry(g,0,ANSWERS.get(0),n->{}));
            entries.add(entry(g,0,ANSWERS.get(0).replace("58 万元","57 万元"),n->{}));
            entries.add(entry(g,0,ANSWERS.get(0)+"保证绝不延期。",n->{}));
            entries.add(entry(g,0,ANSWERS.get(0),n->payload(n,p->p.put("offers",42))));
        }
        var r=analyze(m); assertEquals(State.READY,r.state());
        for(String g:List.of("A","B")) {
            assertEquals(4,metric(r,g,0,Key.COMPLETE_ANSWER).denominator());
            assertEquals(1,count(r,g,0,Key.COMPLETE_ANSWER,"PASS")); assertEquals(1,count(r,g,0,Key.COMPLETE_ANSWER,"FAIL"));
            assertEquals(2,count(r,g,0,Key.COMPLETE_ANSWER,"NEEDS_REVIEW")); assertEquals(0,count(r,g,0,Key.COMPLETE_ANSWER,"ERROR"));
            assertEquals(1,count(r,g,0,Key.SCORING_EXECUTION,"ERROR"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"missing","duplicate","extra","scope","version","forged","stale","foundation"})
    void incomparableManifestCannotSalvagePartialMetrics(String fault) throws Exception {
        var m=manifest(2); var arr=(ArrayNode)m.path("records"); var e=(ObjectNode)arr.get(0);
        switch(fault) {
            case "missing" -> arr.remove(0);
            case "duplicate" -> arr.set(1,e.deepCopy());
            case "extra" -> arr.add(entry("A",0,ANSWERS.get(0),n->{}));
            case "scope" -> ((ObjectNode)m.path("requiredRecordsPerCase")).put("unknown-case",1);
            case "version" -> m.put("datasetVersion","future");
            case "forged" -> Files.writeString(Path.of(e.path("v3Path").asText()),"{\"schemaVersion\":\"procurement-answer-v2\",\"completeAnswerStatus\":\"PASS\"}");
            case "stale" -> {
                Path p=Path.of(e.path("artifactPath").asText()); ObjectNode n=json.valueToTree(ProcurementEvaluationReports.readArtifact(p));
                ((ObjectNode)n.path("runtime")).put("answer","错误回答"); var a=json.treeToValue(n,ProcurementEvaluation.ExecutionArtifact.class);
                ProcurementEvaluationReports.write(p,a); e.put("artifactSha256",ProcurementEvaluationReports.artifactHash(a));
            }
            default -> replace(m,"A",0,ANSWERS.get(0),n->((ObjectNode)n.path("runtime")).put("sessionId","wrong-session"));
        }
        var r=analyze(m); assertEquals(State.BLOCKED,r.state()); assertTrue(r.cases().isEmpty()); assertTrue(r.groupMetrics().isEmpty()); assertTrue(r.differences().isEmpty());
        assertFalse(r.inputValidation().diagnostics().isEmpty());
    }

    @Test void reorderedInputsHaveIdenticalMetricsAndReferences() throws Exception {
        var m=manifest(2); var first=analyze(m);
        for(String k:List.of("groups","records")) { var a=m.path(k); var reversed=json.createArrayNode(); for(int i=a.size()-1;i>=0;i--) reversed.add(a.get(i)); m.set(k,reversed); }
        assertEquals(first,analyze(m));
    }

    @Test void savedScriptedRuntimeIsSeparateFromSyntheticPass() throws Exception {
        String saved=System.getProperty("answer.v3.artifact"); org.junit.jupiter.api.Assumptions.assumeTrue(saved!=null,"Saved Runtime artifact path is opt-in");
        var m=manifest(1); Path path=Path.of(saved).toAbsolutePath(); byte[] before=Files.readAllBytes(path);
        var a=ProcurementEvaluationReports.readArtifact(path); var file=new ProcurementAnswerV3Reports().replay(path,FIXTURE,POLICY,directory.resolve("saved-runtime"));
        var e=new ProcurementExperimentManifest.Entry("saved-runtime","A",caseId(0),a.runtime().runId(),a.runtime().sessionId(),ProcurementEvaluationReports.artifactHash(a),path.toString(),file.toString());
        ((ArrayNode)m.path("records")).set(0,json.valueToTree(e));
        ((ObjectNode)m.path("groups").get(0).path("configuration")).put("dataOrigin","SAVED_SCRIPTED_RUNTIME");
        var r=analyze(m); assertEquals(State.READY,r.state()); assertEquals(1,count(r,"A",0,Key.COMPLETE_ANSWER,"FAIL")); assertEquals(1,count(r,"B",0,Key.COMPLETE_ANSWER,"PASS"));
        var missing=bucket(metric(r,"A",0,Key.REQUIRED_ELEMENTS),"MISSING").sources().stream().map(Source::ref).toList();
        assertTrue(missing.contains("element:delivery_advantage")); assertTrue(missing.contains("element:budget_compliance")); assertArrayEquals(before,Files.readAllBytes(path));
    }

    ObjectNode manifest(int cases) throws Exception {
        var c=ProcurementEvaluationDataset.load().get(0); var m=json.createObjectNode();
        m.put("schemaVersion",ProcurementExperimentManifest.VERSION).put("experimentId","SYNTHETIC_METRICS_TEST")
                .put("datasetVersion",c.datasetVersion()).put("datasetSha256",c.datasetSha256()).put("fixturePath",FIXTURE.toString()).put("policyPath",POLICY.toString());
        var scope=m.putObject("requiredRecordsPerCase"); var groups=m.putArray("groups"); var records=m.putArray("records");
        for(String g:List.of("A","B")) {
            var group=groups.addObject(); group.put("groupId",g).put("model","SYNTHETIC-"+g).put("promptVersion","test").put("agentVersion","test"); group.putObject("configuration").put("dataOrigin","SYNTHETIC");
            for(int i=0;i<cases;i++) { scope.put(caseId(i),1); records.add(entry(g,i,ANSWERS.get(i),n->{})); }
        }
        return m;
    }
    ObjectNode entry(String g,int i,String answer,Consumer<ObjectNode> edit) throws Exception {
        String id=g+"-"+i+"-"+(sequence++); ObjectNode n=json.valueToTree(new ProcurementAnswerAggregationTests().good(i,answer));
        ((ObjectNode)n.path("runtime")).put("runId",id); for(var t:n.path("toolExecutions")) ((ObjectNode)t).put("runId",id); edit.accept(n);
        var a=json.treeToValue(n,ProcurementEvaluation.ExecutionArtifact.class); Path path=directory.resolve(id+".artifact.json"); ProcurementEvaluationReports.write(path,a);
        Path v3=new ProcurementAnswerV3Reports().replay(path,FIXTURE,POLICY,directory.resolve(id+"-v3"));
        return json.valueToTree(new ProcurementExperimentManifest.Entry(id,g,caseId(i),a.runtime().runId(),a.runtime().sessionId(),ProcurementEvaluationReports.artifactHash(a),path.toString(),v3.toString()));
    }
    void replace(ObjectNode m,String g,int i,String text,Consumer<ObjectNode> edit) throws Exception {
        var entries=(ArrayNode)m.path("records"); for(int j=0;j<entries.size();j++) if(entries.get(j).path("groupId").asText().equals(g)&&entries.get(j).path("caseId").asText().equals(caseId(i))) { entries.set(j,entry(g,i,text,edit)); return; }
        fail("Record not found");
    }
    void payload(ObjectNode n,Consumer<ObjectNode> edit) { var r=(ObjectNode)n.path("toolExecutions").get(0).path("result"); ObjectNode p=(ObjectNode)json.readTree(r.path("content").asText()); edit.accept(p); r.put("content",json.writeValueAsString(p)); }
    Result analyze(ObjectNode m) throws Exception { return new ProcurementComparisonAnalyzer().analyze(Files.writeString(directory.resolve("manifest.json"),json.writeValueAsString(m))); }
    String caseId(int i) throws Exception { return ProcurementEvaluationDataset.load().get(i).caseId(); }
    Metric metric(Result r,String g,int i,Key k) throws Exception { String c=caseId(i); return r.cases().stream().filter(x->x.groupId().equals(g)&&x.caseId().equals(c)).findFirst().orElseThrow().metrics().stream().filter(x->x.key()==k).findFirst().orElseThrow(); }
    Bucket bucket(Metric m,String s) { return m.buckets().stream().filter(b->b.status().equals(s)).findFirst().orElseThrow(); }
    long count(Result r,String g,int i,Key k,String s) throws Exception { return bucket(metric(r,g,i,k),s).count(); }
    Map<Path,byte[]> snapshot() throws Exception { var out=new HashMap<Path,byte[]>(); try(var paths=Files.walk(directory)) { for(var p:paths.filter(Files::isRegularFile).toList()) out.put(p,Files.readAllBytes(p)); } return out; }
}
