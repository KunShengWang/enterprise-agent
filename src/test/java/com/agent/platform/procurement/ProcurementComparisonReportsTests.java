package com.agent.platform.procurement;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.ProcurementComparisonMetrics.*;

/** Synthetic Artifact -> official v3 -> Step 1 -> Step 2 -> independent report -> strict read. */
class ProcurementComparisonReportsTests {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final ProcurementComparisonReports reports = new ProcurementComparisonReports();
    ProcurementComparisonMetricsTests samples() { var s=new ProcurementComparisonMetricsTests(); s.directory=directory; return s; }
    Path manifest(ObjectNode m) throws IOException { return Files.writeString(directory.resolve("manifest.json"),json.writeValueAsString(m)); }
    Path save(ObjectNode m,String output) throws Exception { return reports.replay(manifest(m),directory.resolve(output)); }

    @Test void fourCaseReportPreservesStep2AndAllInputBytes() throws Exception {
        var s=samples(); var m=s.manifest(4); Path input=manifest(m); var before=s.snapshot();
        var expected=new ProcurementComparisonAnalyzer().analyze(input);
        Path path=reports.replay(input,directory.resolve("out")); var result=reports.read(path);
        assertEquals(ProcurementComparisonReport.statistics(expected),result.statistics());
        assertEquals(expected.recordEvidence(),result.recordEvidence()); assertEquals(expected.inputValidation(),result.inputValidation());
        assertEquals(8,result.statistics().cases().size());
        assertTrue(result.recordEvidence().values().stream().allMatch(v->v.completeAnswerStatus()==ProcurementAnswerEvaluationV3.CompleteAnswerStatus.PASS));
        for(var v:result.recordEvidence().values()) {
            assertEquals(ProcurementEvaluation.Status.SKIP,v.completenessEvaluation().baseEvaluation().completeAnswerStatus());
            assertEquals(ProcurementEvaluation.Status.SKIP,v.completenessEvaluation().baseEvaluation().legacyEvaluation().completeAnswerStatus());
        }
        for(var p:before.keySet()) assertArrayEquals(before.get(p),Files.readAllBytes(p),p.toString());
        assertFalse(Files.readString(path).contains(directory.toString().replace("\\","\\\\")));
    }

    @Test void relocationReorderingAndRepeatedReplayProduceIdenticalBytes() throws Exception {
        var s=samples(); var m=s.manifest(2); Path first=save(m,"first");
        Path second=save(m,"second"); assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        Path relocated=Files.createDirectory(directory.resolve("relocated-inputs"));
        for(String field:List.of("fixturePath","policyPath")) {
            Path copy=Files.copy(Path.of(m.path(field).asText()),relocated.resolve(field+".json")); m.put(field,copy.toString());
        }
        for(var raw:m.path("records")) {
            var e=(ObjectNode)raw;
            Path rawCopy=Files.copy(Path.of(e.path("artifactPath").asText()),relocated.resolve(e.path("recordId").asText()+".json"));
            Path v3=Path.of(e.path("v3Path").asText()); Path v3Copy=Files.copy(v3,relocated.resolve(v3.getFileName()));
            e.put("artifactPath",rawCopy.toString()).put("v3Path",v3Copy.toString());
        }
        for(String field:List.of("groups","records")) {
            var old=m.path(field); var arr=json.createArrayNode(); for(int i=old.size()-1;i>=0;i--) arr.add(old.get(i)); m.set(field,arr);
        }
        Path movedManifest=Files.writeString(relocated.resolve("manifest.json"),json.writeValueAsString(m));
        Path third=reports.replay(movedManifest,directory.resolve("third"));
        assertEquals(first.getFileName(),third.getFileName()); assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(third));
        assertEquals(reports.read(first),reports.read(third));
    }

    @Test void equalRatiosRetainDifferentNumeratorsDenominatorsAndSources() throws Exception {
        var s=samples(); var m=s.manifest(1);
        s.replace(m,"A",0,"Supplier D 总价 58 万元。Supplier D 交期 99 天。",n->{});
        s.replace(m,"B",0,"Supplier D 总价 58 万元。Supplier D 交期 99 天。Supplier B 总价 55 万元。Supplier B 交期 99 天。",n->{});
        var r=reports.read(save(m,"out"));
        var d=difference(r,Key.SCALAR_FACT,"PASS");
        assertEquals(1,d.leftProportion().numerator()); assertEquals(2,d.leftProportion().denominator());
        assertEquals(2,d.rightProportion().numerator()); assertEquals(4,d.rightProportion().denominator());
        assertEquals(0,new BigDecimal("0.5").compareTo(d.leftProportion().value())); assertEquals(0,d.proportionDelta().signum());
        assertEquals(1,d.leftSources().size()); assertEquals(2,d.rightSources().size()); assertNotEquals(d.leftProportion(),d.rightProportion());
    }

    @Test void equalFailStatusRetainsMissingElementAndWrongFactSeparately() throws Exception {
        var s=samples(); var m=s.manifest(1);
        s.replace(m,"A",0,ProcurementComparisonMetricsTests.ANSWERS.get(0).replace("D 比 B 快 6 天。",""),n->{});
        s.replace(m,"B",0,ProcurementComparisonMetricsTests.ANSWERS.get(0).replace("58 万元","57 万元"),n->{});
        var r=reports.read(save(m,"out")); var status=difference(r,Key.COMPLETE_ANSWER,"FAIL");
        assertEquals(1,status.leftCount()); assertEquals(1,status.rightCount()); assertEquals(0,status.countDelta());
        assertEquals(0,status.proportionDelta().signum());
        var missing=difference(r,Key.REQUIRED_ELEMENTS,"MISSING"); assertEquals(-1,missing.countDelta());
        assertEquals("element:delivery_advantage",missing.leftSources().get(0).ref());
        var fact=difference(r,Key.SCALAR_FACT,"FAIL"); assertEquals(1,fact.countDelta());
        var source=fact.rightSources().get(0); assertTrue(source.ref().startsWith("scalar:claim-"));
        assertFalse(source.reason().isBlank()); assertNotEquals(missing.leftSources().get(0).recordId(),source.recordId());
        assertTrue(r.recordEvidence().get(source.recordId()).findings().stream().anyMatch(f->f.kind()==ProcurementAnswerEvaluationV3.FindingKind.FAIL));
    }

    @Test void uncomputableSideRetainsCountsExclusionsAndNullDifference() throws Exception {
        var s=samples(); var m=s.manifest(1); s.replace(m,"B",0,ProcurementComparisonMetricsTests.ANSWERS.get(0).replace("快 6 天","快"),n->{});
        var r=reports.read(save(m,"out")); var d=difference(r,Key.RELATION_DIFFERENCE,"PASS");
        assertEquals(Calculation.CALCULABLE,d.leftProportion().calculation()); assertEquals(Calculation.NOT_COMPUTABLE,d.rightProportion().calculation());
        assertEquals(0,d.rightProportion().denominator()); assertNull(d.proportionDelta()); assertEquals(Calculation.NOT_COMPUTABLE,d.calculation());
        assertTrue(r.statistics().cases().stream().filter(c->c.groupId().equals("B")).flatMap(c->c.metrics().stream()).filter(v->v.key()==Key.RELATION_DIFFERENCE)
                .flatMap(v->v.exclusions().stream()).anyMatch(e->e.reason().startsWith("NOT_APPLICABLE:")));
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void syntheticPassIsRevokedInReportWhenUnknownPromiseAdded(int index) throws Exception {
        var s=samples(); var m=s.manifest(4); s.replace(m,"B",index,ProcurementComparisonMetricsTests.ANSWERS.get(index)+"保证绝不延期。",n->{});
        var r=reports.read(save(m,"out")); String c=s.caseId(index);
        var changed=r.inputValidation().records().stream().filter(x->x.groupId().equals("B")&&x.caseId().equals(c)).findFirst().orElseThrow();
        assertNotEquals(ProcurementAnswerEvaluationV3.CompleteAnswerStatus.PASS,changed.completeAnswerStatus());
        assertFalse(r.recordEvidence().get(changed.recordId()).findings().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"artifact","v3","v1","v2","fixture","policy","history"})
    void existingFilesAndHardLinkAliasesCannotBeOverwritten(String kind) throws Exception {
        var s=samples(); var m=s.manifest(1); Path input=manifest(m); Path first=reports.replay(input,directory.resolve("first"));
        Path protectedFile=switch(kind) {
            case "artifact" -> Path.of(m.path("records").get(0).path("artifactPath").asText());
            case "v3" -> Path.of(m.path("records").get(0).path("v3Path").asText());
            case "fixture" -> Files.copy(ProcurementComparisonMetricsTests.FIXTURE,directory.resolve("fixture-copy.json"));
            case "policy" -> Files.copy(ProcurementComparisonMetricsTests.POLICY,directory.resolve("policy-copy.json"));
            default -> Files.writeString(directory.resolve(kind+".json"),"protected historical content");
        };
        byte[] before=Files.readAllBytes(protectedFile);
        assertThrows(IOException.class,()->reports.replay(input,protectedFile));
        Path collision=Files.createDirectory(directory.resolve("collision")); Files.createLink(collision.resolve(first.getFileName()),protectedFile);
        assertThrows(FileAlreadyExistsException.class,()->reports.replay(input,collision));
        assertThrows(FileAlreadyExistsException.class,()->reports.replay(input,first.getParent()));
        assertArrayEquals(before,Files.readAllBytes(protectedFile));
    }

    @ParameterizedTest @ValueSource(strings={"exception","short","collision"})
    void writeFailureNeverPublishesPartialReport(String fault) throws Exception {
        var s=samples(); Path input=manifest(s.manifest(1)); Path out=directory.resolve("out");
        var failing=new ProcurementComparisonReports((path,bytes)->{
            if(fault.equals("collision")) { Files.write(path,bytes); Files.writeString(path.getParent().resolve(ProcurementComparisonReports.fileName(bytes)),"occupied"); }
            else { Files.write(path,Arrays.copyOf(bytes,10)); if(fault.equals("exception")) throw new IOException("injected"); }
        });
        assertThrows(IOException.class,()->failing.replay(input,out));
        try(var paths=Files.list(out)) {
            var files=paths.toList(); if(fault.equals("collision")) { assertEquals(1,files.size()); assertEquals("occupied",Files.readString(files.get(0))); assertThrows(IOException.class,()->reports.read(files.get(0))); }
            else assertTrue(files.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"missing","duplicate","stale","foundation"})
    void invalidInputsProduceNoFormalReport(String fault) throws Exception {
        var s=samples(); var m=s.manifest(1); var entries=(ArrayNode)m.path("records");
        switch(fault) {
            case "missing" -> entries.remove(0);
            case "duplicate" -> entries.set(1,entries.get(0).deepCopy());
            case "foundation" -> s.replace(m,"A",0,ProcurementComparisonMetricsTests.ANSWERS.get(0),n->((ObjectNode)n.path("runtime")).put("sessionId","bad"));
            default -> Files.writeString(Path.of(entries.get(0).path("artifactPath").asText()),"{}");
        }
        Path input=manifest(m),out=directory.resolve("out");
        var e=assertThrows(IOException.class,()->reports.replay(input,out)); assertTrue(e.getMessage().contains("COMPARISON_NOT_READY")); assertFalse(Files.exists(out));
    }

    @ParameterizedTest @ValueSource(strings={"schema","version","missing","missingZero","null","enum","unknown","numerator","source","exclusion","macro","difference","binding","caseMembership","duplicate","trailing","rename","content","v1","v2","v3"})
    void invalidInternallyRehashedReportsAreRejected(String fault) throws Exception {
        var s=samples(); Path good=save(s.manifest(1),"good"); ObjectNode tree=(ObjectNode)json.readTree(Files.readAllBytes(good));
        switch(fault) {
            case "schema" -> tree.put("schemaVersion","future");
            case "version" -> ((ObjectNode)tree.path("statistics")).put("metricsVersion","future");
            case "missing" -> tree.remove("inputValidation");
            case "missingZero" -> ((ObjectNode)tree.path("statistics").path("differences").get(0)).remove("countDelta");
            case "null" -> tree.putNull("recordEvidence");
            case "enum" -> ((ObjectNode)tree.path("inputValidation")).put("eligibility","FORGED");
            case "unknown" -> tree.put("passRate",1);
            case "numerator" -> ((ObjectNode)tree.path("statistics").path("cases").get(0).path("metrics").get(0).path("successRatio")).put("numerator",20);
            case "source" -> ((ObjectNode)tree.path("statistics").path("cases").get(0).path("metrics").get(0).path("buckets").get(0).path("sources").get(0)).put("recordId","another-group");
            case "exclusion" -> ((ObjectNode)tree.path("statistics").path("cases").get(0).path("metrics").get(1)).set("exclusions",json.createArrayNode());
            case "macro" -> ((ObjectNode)tree.path("statistics").path("groupMetrics").get(0).path("macro")).put("value",0);
            case "difference" -> ((ObjectNode)tree.path("statistics").path("differences").get(0)).put("proportionDelta",1);
            case "binding" -> ((ObjectNode)tree.path("inputValidation").path("records").get(0).path("inputBinding")).put("runId","wrong");
            case "caseMembership" -> ((ObjectNode)tree.path("inputValidation").path("cases").get(0)).put("caseId","wrong");
            case "v1","v2","v3" -> {
                var v=tree.path("recordEvidence").properties().iterator().next().getValue();
                tree=(ObjectNode)(fault.equals("v3")?v:fault.equals("v2")?v.path("completenessEvaluation").path("baseEvaluation"):v.path("completenessEvaluation").path("baseEvaluation").path("legacyEvaluation")).deepCopy();
            }
            default -> { }
        }
        String text=ProcurementEvaluationReports.canonicalJson(tree);
        if(fault.equals("duplicate")) text=text.replaceFirst("\\{","{\"schemaVersion\":\"forged\",");
        if(fault.equals("trailing")) text+=" {}";
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); String name=fault.equals("rename")?"wrong.json":ProcurementComparisonReports.fileName(bytes);
        if(fault.equals("content")) bytes="{}".getBytes(StandardCharsets.UTF_8);
        Path bad=Files.write(directory.resolve(name),bytes); assertThrows(IOException.class,()->reports.read(bad));
        assertNotNull(reports.read(good));
    }

    @Test void savedReportReadDoesNotClaimFreshArtifactAgreement() throws Exception {
        var s=samples(); var m=s.manifest(1); Path good=save(m,"out"); var before=reports.read(good);
        Files.writeString(Path.of(m.path("records").get(0).path("artifactPath").asText()),"{}");
        assertEquals(before,reports.read(good)); assertThrows(IOException.class,()->save(m,"new-output"));
        assertTrue(before.limitations().contains("READ_IS_NOT_CURRENT_INPUT_REPLAY"));
    }

    @Test @EnabledIfSystemProperty(named="answer.v3.artifact",matches=".+")
    void savedRuntimeFailAndDiagnosticsMatchStep2() throws Exception {
        var s=samples(); var m=s.manifest(1); Path path=Path.of(System.getProperty("answer.v3.artifact")).toAbsolutePath(); byte[] before=Files.readAllBytes(path);
        var a=ProcurementEvaluationReports.readArtifact(path); Path v3=new ProcurementAnswerV3Reports().replay(path,ProcurementComparisonMetricsTests.FIXTURE,ProcurementComparisonMetricsTests.POLICY,directory.resolve("runtime-v3"));
        var e=new ProcurementExperimentManifest.Entry("runtime","A",s.caseId(0),a.runtime().runId(),a.runtime().sessionId(),ProcurementEvaluationReports.artifactHash(a),path.toString(),v3.toString());
        ((ArrayNode)m.path("records")).set(0,json.valueToTree(e)); ((ObjectNode)m.path("groups").get(0).path("configuration")).put("dataOrigin","SAVED_SCRIPTED_RUNTIME");
        var expected=new ProcurementComparisonAnalyzer().analyze(manifest(m)); var actual=reports.read(save(m,"out"));
        assertEquals(ProcurementComparisonReport.statistics(expected),actual.statistics()); assertEquals(expected.recordEvidence(),actual.recordEvidence());
        assertEquals(ProcurementAnswerEvaluationV3.CompleteAnswerStatus.FAIL,actual.recordEvidence().get("runtime").completeAnswerStatus());
        var missing=difference(actual,Key.REQUIRED_ELEMENTS,"MISSING").leftSources().stream().map(Source::ref).toList();
        assertTrue(missing.containsAll(List.of("element:delivery_advantage","element:budget_compliance"))); assertArrayEquals(before,Files.readAllBytes(path));
    }
    Difference difference(ProcurementComparisonReport r,Key k,String status) { return r.statistics().differences().stream().filter(d->d.metric()==k&&d.status().equals(status)).findFirst().orElseThrow(); }
}
