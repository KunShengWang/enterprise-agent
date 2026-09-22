package com.agent.platform.procurement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static com.agent.platform.procurement.ProcurementAnswerCoverage.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementAnswerCoverageTests {
    private final ProcurementAnswerCoverage analyzer = new ProcurementAnswerCoverage();

    @ParameterizedTest
    @ValueSource(strings = {"谢谢", "谢谢您", "您好", "你好", "感谢您的帮助", "以下是建议"})
    void wholePoliteFragmentDoesNotBecomeUnknown(String polite) {
        String answer = "推荐 Supplier D，总价 58 万元，交期 12 天。" + polite + "。";
        var result = analyze(answer);
        assertFalse(result.hasUnresolvedContent(), result.toString());
        assertTrue(result.counts().get(Kind.PARSED_CLAIM) > 0);
        assertTrue(result.fragments().stream().anyMatch(f -> f.text().equals(polite) && f.kind() == Kind.NON_FACTUAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"谢谢，供应商保证绝不延期。", "谢谢供应商保证交付。", "您好，本公司信誉全球第一。",
            "推荐 Supplier D，总价 58 万元。谢谢，保证质量绝对可靠。"})
    void politePrefixNeverErasesBusinessPromise(String answer) {
        var result = analyze(answer);
        assertTrue(result.hasUnresolvedContent());
        assertTrue(result.fragments().stream().anyMatch(f -> f.kind() == Kind.UNRESOLVED_BUSINESS));
        assertTrue(result.fragments().stream().filter(f -> f.text().contains("保证") || f.text().contains("信誉"))
                .noneMatch(f -> f.kind() == Kind.NON_FACTUAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"推荐 Supplier D 的附加服务", "总价五十八万元", "交期很快", "RFQ 操作已经彻底搞定"})
    void unsupportedBusinessClauseIsRetainedWhole(String answer) {
        var result = analyze(answer);
        assertEquals(1, result.fragments().size());
        assertEquals(answer, result.fragments().get(0).text());
        assertEquals(Kind.UNRESOLVED_BUSINESS, result.fragments().get(0).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"星河璀璨", "这事稳了", "😀𠮷", "谢谢你的魔法"})
    void unrecognizedContentIsNotAssumedHarmless(String unknown) {
        var result = analyze("推荐 Supplier D。" + unknown + "。");
        assertTrue(result.counts().get(Kind.PARSED_CLAIM) > 0);
        assertTrue(result.fragments().stream().anyMatch(f -> f.text().equals(unknown) && f.kind() == Kind.UNKNOWN_CONTENT));
        assertTrue(result.hasUnresolvedContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果批准，推荐 Supplier D。", "推荐 Supplier D。前提是得到批准。", "推荐 Supplier D 吗", "推荐 Supplier D？",
            "不推荐 Supplier D。", "推荐 Supplier D，并非如此。", "若批准；推荐 Supplier D。", "“推荐 Supplier D”，数量 50 台。",
            "推荐 Supplier D，数量 50 台。以上是引用。"})
    void qualifiersAndQuotationDoNotBecomeUnconditionalCoverage(String answer) {
        var result = analyze(answer);
        assertEquals(0, result.counts().get(Kind.PARSED_CLAIM));
        assertTrue(result.hasUnresolvedContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"😀𠮷。推荐 Supplier D，总价 58 万元。谢谢", "  Supplier D 的单价 11600 元。🛰️", "推荐 Supplier D：总价 580000，交期 12 天；备选 Supplier B：总价 550000，交期 18 天。"})
    void utf16CoverageIncludesConsumedSubjectPrefixesAndEveryOriginalCharacter(String answer) {
        analyze(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n", "！！！", "谢谢。"})
    void noFactAnswerDoesNotProduceParsedClaims(String answer) {
        var result = analyze(answer);
        assertEquals(0, result.counts().get(Kind.PARSED_CLAIM));
        assertEquals(answer.isBlank(), result.emptyAnswer());
    }

    @Test
    void appendUnverifiableClaimBreaksResolvableCoverage() {
        String base = "推荐 Supplier D，总价 580000 元，交期 12 天。谢谢。";
        assertFalse(analyze(base).hasUnresolvedContent());
        assertTrue(analyze(base + "供应商拥有全球最高信誉。").hasUnresolvedContent());
        assertTrue(analyze(base + "这事稳了。").hasUnresolvedContent());
    }

    @Test
    void syntacticallyParsedStateIsKeptAndIsNotAProofOfExecution() {
        var result = analyze("RFQ 已创建。");
        var state = result.claims().get(0);
        assertEquals("RFQ_CREATED", state.claim().property());
        assertTrue(result.fragments().stream().anyMatch(f -> f.kind() == Kind.PARSED_CLAIM && f.claimIds().contains(state.claimId())));
    }

    @Test
    void adapterNeverReextractsAfterRemovingPoliteness() {
        String answer = "推荐 Supplier D，谢谢，交期 12 天。";
        var result = analyze(answer);
        assertEquals(new ProcurementAnswerClaimExtractor().extract(answer), result.claims().stream().map(Extraction::claim).toList());
        assertTrue(result.fragments().stream().anyMatch(f -> f.text().contains("交期") && f.kind() == Kind.UNRESOLVED_BUSINESS));
    }

    private Analysis analyze(String answer) {
        var result = analyzer.analyze(answer);
        assertEquals(answer, result.fragments().stream().map(Fragment::text).reduce("", String::concat));
        int end = 0;
        List<String> ids = result.claims().stream().map(Extraction::claimId).toList();
        for (var fragment : result.fragments()) {
            assertEquals(end, fragment.start());
            assertEquals(answer.substring(fragment.start(), fragment.end()), fragment.text());
            for (int boundary : List.of(fragment.start(), fragment.end()))
                assertFalse(boundary > 0 && boundary < answer.length() && Character.isHighSurrogate(answer.charAt(boundary - 1))
                        && Character.isLowSurrogate(answer.charAt(boundary)));
            assertTrue(ids.containsAll(fragment.claimIds()));
            if (fragment.kind() == Kind.PARSED_CLAIM) assertFalse(fragment.claimIds().isEmpty());
            end = fragment.end();
        }
        assertEquals(answer.length(), end);
        assertEquals(result.fragments().size(), result.counts().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(result, analyzer.analyze(answer));
        return result;
    }
}
