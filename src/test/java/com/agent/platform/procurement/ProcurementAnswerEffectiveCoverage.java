package com.agent.platform.procurement;

import java.util.*;
import static com.agent.platform.procurement.ProcurementAnswerCoverage.Kind;

/** Parse-only overlay. Neither source coverage nor any factual verdict is replaced. */
public final class ProcurementAnswerEffectiveCoverage {
    public static final String VERSION = "procurement-effective-coverage-v1";
    public enum EffectiveKind { PARSED_SCALAR, PARSED_RELATION, NON_FACTUAL, UNKNOWN_CONTENT, UNRESOLVED_BUSINESS }
    public record Segment(int originalFragmentIndex, EffectiveKind kind, List<String> claimRefs, String reason) {
        public Segment { claimRefs = List.copyOf(claimRefs); }
    }
    public record Analysis(String version, ProcurementAnswerCoverage.Analysis original,
                           List<Segment> segments, boolean hasUnresolvedContent) {
        public Analysis {
            segments = List.copyOf(segments);
            if (!VERSION.equals(version) || original == null || segments.size() != original.fragments().size())
                throw new IllegalArgumentException("INVALID_EFFECTIVE_COVERAGE");
            String answer = original.fragments().stream().map(ProcurementAnswerCoverage.Fragment::text)
                    .collect(java.util.stream.Collectors.joining());
            if (!original.equals(new ProcurementAnswerCoverage().analyze(answer)))
                throw new IllegalArgumentException("INVALID_ORIGINAL_COVERAGE");
            var relations = new ProcurementAnswerRelationExtractor().extract(answer);
            for (int i = 0; i < segments.size(); i++) {
                var s = segments.get(i);
                if (s.originalFragmentIndex() != i || s.kind() == null || s.reason() == null || s.reason().isBlank())
                    throw new IllegalArgumentException("INVALID_EFFECTIVE_SEGMENT");
                var f = original.fragments().get(i);
                boolean valid = switch (f.kind()) {
                    case PARSED_CLAIM -> s.kind() == EffectiveKind.PARSED_SCALAR
                            && s.claimRefs().equals(f.claimIds().stream().map(id -> "scalar:" + id).toList());
                    case NON_FACTUAL -> s.kind() == EffectiveKind.NON_FACTUAL && s.claimRefs().isEmpty();
                    case UNKNOWN_CONTENT -> s.kind() == EffectiveKind.UNKNOWN_CONTENT && s.claimRefs().isEmpty()
                            || s.kind() == EffectiveKind.PARSED_RELATION && s.claimRefs().size() == 1;
                    case UNRESOLVED_BUSINESS -> s.kind() == EffectiveKind.UNRESOLVED_BUSINESS && s.claimRefs().isEmpty()
                            || s.kind() == EffectiveKind.PARSED_RELATION && s.claimRefs().size() == 1;
                };
                if (!valid) throw new IllegalArgumentException("ILLEGAL_COVERAGE_RECLASSIFICATION");
                if (s.kind() == EffectiveKind.PARSED_RELATION) {
                    String ref = s.claimRefs().get(0);
                    if (!ref.matches("relation:(0|[1-9][0-9]*)")) throw new IllegalArgumentException("INVALID_RELATION_REFERENCE");
                    int index = Integer.parseInt(ref.substring(9));
                    if (index >= relations.size()) throw new IllegalArgumentException("MISSING_RELATION_REFERENCE");
                    var claim = relations.get(index);
                    int start = f.start(), end = f.end();
                    while (start < end && Character.isWhitespace(answer.charAt(start))) start++;
                    while (end > start && Character.isWhitespace(answer.charAt(end - 1))) end--;
                    if (start == end || claim.start() != start || claim.end() != end || !claim.modality().equals("ASSERTED")
                            || !claim.unresolvedReason().isEmpty() || !claim.text().equals(answer.substring(start, end)))
                        throw new IllegalArgumentException("RELATION_FRAGMENT_MISMATCH");
                }
            }
            boolean unresolved = original.emptyAnswer() || segments.stream().anyMatch(s -> unresolved(s.kind()));
            if (unresolved != hasUnresolvedContent) throw new IllegalArgumentException("EFFECTIVE_COVERAGE_FLAG_MISMATCH");
        }
    }
    public Analysis analyze(String answer, ProcurementAnswerCoverage.Analysis original,
                            List<ProcurementAnswerRelationGrader.Result> relations) {
        String raw = answer == null ? "" : answer;
        if (!original.equals(new ProcurementAnswerCoverage().analyze(raw))
                || !relations.stream().map(ProcurementAnswerRelationGrader.Result::claim).toList()
                .equals(new ProcurementAnswerRelationExtractor().extract(raw)))
            throw new IllegalArgumentException("COVERAGE_RELATIONS_ANSWER_MISMATCH");
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < original.fragments().size(); i++) {
            var f = original.fragments().get(i);
            EffectiveKind kind = switch (f.kind()) {
                case PARSED_CLAIM -> EffectiveKind.PARSED_SCALAR;
                case NON_FACTUAL -> EffectiveKind.NON_FACTUAL;
                case UNKNOWN_CONTENT -> EffectiveKind.UNKNOWN_CONTENT;
                case UNRESOLVED_BUSINESS -> EffectiveKind.UNRESOLVED_BUSINESS;
            };
            List<String> refs = kind == EffectiveKind.PARSED_SCALAR ? f.claimIds().stream().map(id -> "scalar:" + id).toList() : List.of();
            String reason = "ORIGINAL_CLASSIFICATION_RETAINED";
            if (f.kind() == Kind.UNKNOWN_CONTENT || f.kind() == Kind.UNRESOLVED_BUSINESS) {
                int start = f.start(), end = f.end();
                while (start < end && Character.isWhitespace(raw.charAt(start))) start++;
                while (end > start && Character.isWhitespace(raw.charAt(end - 1))) end--;
                for (int j = 0; j < relations.size(); j++) {
                    var claim = relations.get(j).claim();
                    if (claim.start() == start && claim.end() == end && end > start
                            && claim.text().equals(raw.substring(start, end)) && claim.modality().equals("ASSERTED")
                            && claim.unresolvedReason().isEmpty()) {
                        kind = EffectiveKind.PARSED_RELATION; refs = List.of("relation:" + j);
                        reason = "WHOLE_FRAGMENT_PARSED_AS_RELATION; verdicts unchanged"; break;
                    }
                }
            }
            segments.add(new Segment(i, kind, refs, reason));
        }
        return new Analysis(VERSION, original, segments, original.emptyAnswer() || segments.stream().anyMatch(s -> unresolved(s.kind())));
    }
    static boolean unresolved(EffectiveKind kind) {
        return kind == EffectiveKind.UNKNOWN_CONTENT || kind == EffectiveKind.UNRESOLVED_BUSINESS;
    }
}
