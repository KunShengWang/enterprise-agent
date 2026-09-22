package com.agent.platform.procurement;

import java.util.*;
import java.util.regex.Pattern;
import static com.agent.platform.procurement.ProcurementAnswerEvaluation.AnswerClaim;

/** Coverage bookkeeping around the unchanged v1.1 extractor, not a second scalar parser. */
public final class ProcurementAnswerCoverage {
    public static final String VERSION = "procurement-answer-coverage-v1";
    public enum Kind { PARSED_CLAIM, UNRESOLVED_BUSINESS, NON_FACTUAL, UNKNOWN_CONTENT }
    public record Fragment(String text, int start, int end, Kind kind, String reason, List<String> claimIds) {
        public Fragment {
            claimIds = List.copyOf(claimIds);
            require(text != null && start >= 0 && end >= start && text.length() == end - start
                    && kind != null && reason != null && !reason.isBlank(), "INVALID_FRAGMENT");
            require(new HashSet<>(claimIds).size() == claimIds.size(), "DUPLICATE_CLAIM_REFERENCE");
        }
    }
    public record Extraction(String claimId, AnswerClaim claim) { }
    public record Analysis(String analyzerVersion, String extractorVersion, List<Extraction> claims,
                           List<Fragment> fragments, Map<Kind, Long> counts,
                           boolean emptyAnswer, boolean hasUnresolvedContent) {
        public Analysis {
            claims = List.copyOf(claims); fragments = List.copyOf(fragments); counts = Map.copyOf(counts);
            require(VERSION.equals(analyzerVersion) && ProcurementAnswerClaimExtractor.VERSION.equals(extractorVersion), "COVERAGE_VERSION_MISMATCH");
            require(!fragments.isEmpty(), "MISSING_FRAGMENTS");
            StringBuilder raw = new StringBuilder();
            for (var f : fragments) {
                require(f.start() == raw.length(), "NON_CONTIGUOUS_FRAGMENTS");
                require(f.end() > f.start() || fragments.size() == 1, "EMPTY_INTERIOR_FRAGMENT");
                raw.append(f.text());
            }
            String answer = raw.toString();
            Map<String, AnswerClaim> byId = new HashMap<>();
            for (var extraction : claims) {
                require(extraction.claimId() != null && !extraction.claimId().isBlank() && extraction.claim() != null
                        && byId.putIfAbsent(extraction.claimId(), extraction.claim()) == null, "INVALID_CLAIM_ID");
                var c = extraction.claim();
                require(c.start() >= 0 && c.end() >= c.start() && c.end() <= answer.length()
                        && answer.substring(c.start(), c.end()).equals(c.text()), "CLAIM_SPAN_MISMATCH");
            }
            for (var f : fragments) {
                require(!splitsSurrogate(answer, f.start()) && !splitsSurrogate(answer, f.end()), "SPLIT_SURROGATE");
                var expected = claims.stream().filter(c -> c.claim().end() > c.claim().start()
                        && c.claim().start() >= f.start() && c.claim().end() <= f.end()).map(Extraction::claimId).toList();
                require(expected.equals(f.claimIds()), "CLAIM_REFERENCE_MISMATCH");
                if (f.kind() == Kind.PARSED_CLAIM)
                    require(!expected.isEmpty() && expected.stream().allMatch(id -> byId.get(id).unresolvedReason().isEmpty()), "UNRESOLVED_PARSED_CLAIM");
                if (f.kind() == Kind.NON_FACTUAL)
                    require(f.text().isBlank() || SEPARATORS.matcher(f.text()).matches() || NON_FACTUAL.contains(f.text().strip()), "UNPROVEN_NON_FACTUAL");
            }
            Map<Kind, Long> actual = new EnumMap<>(Kind.class);
            for (var kind : Kind.values()) actual.put(kind, fragments.stream().filter(f -> f.kind() == kind).count());
            require(actual.equals(counts), "COVERAGE_COUNTS_MISMATCH");
            require(emptyAnswer == answer.isBlank() && hasUnresolvedContent == (emptyAnswer
                    || fragments.stream().anyMatch(f -> f.kind() == Kind.UNKNOWN_CONTENT || f.kind() == Kind.UNRESOLVED_BUSINESS)), "COVERAGE_FLAGS_MISMATCH");
        }
    }
    private static boolean splitsSurrogate(String s, int at) {
        return at > 0 && at < s.length() && Character.isHighSurrogate(s.charAt(at - 1)) && Character.isLowSurrogate(s.charAt(at));
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    private static final Set<String> NON_FACTUAL = Set.of("谢谢", "谢谢您", "感谢您的帮助", "您好", "你好", "以下是建议");
    // Only syntax separators used by the existing extractor. Other punctuation/text is not silently discarded.
    private static final Pattern PARTS = Pattern.compile("[，,；;。！!？?\\r\\n]+|[^，,；;。！!？?\\r\\n]+");
    private static final Pattern SEPARATORS = Pattern.compile("[，,；;。！!？?\\r\\n\\s]+");
    private static final Pattern BUSINESS = Pattern.compile("Supplier|供应商|推荐|备选|报价|单价|总价|金额|币种|采购|数量|交期|交付|预算|审批|RFQ|执行|创建|保修|保证|承诺|延期|质量|信誉|合格|约束|[0-9]", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTATION = Pattern.compile("[\"'“”‘’「」『』《》]|引用|原话|声称|听说");

    public Analysis analyze(String answer) {
        String raw = answer == null ? "" : answer;
        List<Extraction> claims = new ArrayList<>();
        var extracted = new ProcurementAnswerClaimExtractor().extract(answer);
        for (int i = 0; i < extracted.size(); i++) claims.add(new Extraction("claim-" + i, extracted.get(i)));
        List<Fragment> fragments = new ArrayList<>();
        boolean quotation = QUOTATION.matcher(raw).find();
        var parts = PARTS.matcher(raw);
        while (parts.find()) {
            int start = parts.start(), end = parts.end();
            String text = raw.substring(start, end), stripped = text.strip();
            var linked = claims.stream().filter(c -> c.claim().end() > c.claim().start()
                    && c.claim().start() >= start && c.claim().end() <= end).toList();
            Kind kind;
            String reason;
            if (SEPARATORS.matcher(text).matches() || stripped.isEmpty()) {
                kind = Kind.NON_FACTUAL; reason = "SYNTAX_OR_WHITESPACE";
            } else if (NON_FACTUAL.contains(stripped)) {
                kind = Kind.NON_FACTUAL; reason = "EXACT_WHOLE_FRAGMENT_ALLOWLIST";
            } else if (quotation) {
                kind = BUSINESS.matcher(text).find() || linked.stream().anyMatch(c -> !c.claim().property().equals("UNRESOLVED"))
                        ? Kind.UNRESOLVED_BUSINESS : Kind.UNKNOWN_CONTENT;
                reason = "QUOTATION_SCOPE_UNRESOLVED";
            } else if (!linked.isEmpty() && linked.stream().allMatch(c -> c.claim().unresolvedReason().isEmpty())) {
                kind = Kind.PARSED_CLAIM; reason = "EXISTING_EXTRACTOR_PARSED; not a verification verdict";
            } else if (BUSINESS.matcher(text).find() || linked.stream().anyMatch(c -> !c.claim().property().equals("UNRESOLVED")
                    || c.claim().unresolvedReason().equals("NEGATION_CONDITION_TENSE_OR_HEDGE"))) {
                kind = Kind.UNRESOLVED_BUSINESS; reason = "BUSINESS_OR_QUALIFIED_EXPRESSION_UNRESOLVED";
            } else {
                kind = Kind.UNKNOWN_CONTENT; reason = "NOT_PARSED_AND_NOT_PROVEN_NON_FACTUAL";
            }
            // Whole clauses include subject prefixes consumed by the extractor. No input characters are lost.
            fragments.add(new Fragment(text, start, end, kind, reason, linked.stream().map(Extraction::claimId).toList()));
        }
        if (fragments.isEmpty()) fragments.add(new Fragment("", 0, 0, Kind.UNKNOWN_CONTENT, "EMPTY_ANSWER", List.of()));
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        for (var kind : Kind.values()) counts.put(kind, fragments.stream().filter(f -> f.kind() == kind).count());
        boolean empty = raw.isBlank();
        boolean unresolved = empty || fragments.stream().anyMatch(f -> f.kind() == Kind.UNKNOWN_CONTENT || f.kind() == Kind.UNRESOLVED_BUSINESS);
        return new Analysis(VERSION, ProcurementAnswerClaimExtractor.VERSION, claims, fragments, counts, empty, unresolved);
    }
}
