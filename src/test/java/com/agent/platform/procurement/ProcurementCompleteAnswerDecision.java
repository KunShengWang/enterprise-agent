package com.agent.platform.procurement;

import java.util.*;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;
import static com.agent.platform.procurement.ProcurementAnswerEvaluationV3.CompleteAnswerStatus;

/** Internal decision table only. The Step 1 raw-input evaluator never claims applicability is complete. */
final class ProcurementCompleteAnswerDecision {
    static final String VERSION = "procurement-complete-answer-decision-v1";
    record Decision(CompleteAnswerStatus status, boolean executionError) { }
    private ProcurementCompleteAnswerDecision() { }

    static Decision decide(boolean trustedFoundation, boolean applicabilityCompleted, List<Status> applicableChecks) {
        return decide(trustedFoundation, applicabilityCompleted, applicableChecks, Set.of());
    }

    /** Only explicitly declared absent relation-difference checks may be NOT_APPLICABLE. */
    static Decision decide(boolean trustedFoundation, boolean applicabilityCompleted, List<Status> applicableChecks,
                           Set<Integer> noDifferenceChecks) {
        applicableChecks = List.copyOf(applicableChecks);
        noDifferenceChecks = Set.copyOf(noDifferenceChecks);
        if (!trustedFoundation) return new Decision(CompleteAnswerStatus.ERROR, true);
        boolean error = applicableChecks.contains(Status.ERROR);
        for (int i = 0; i < applicableChecks.size(); i++)
            if ((applicableChecks.get(i) == Status.NOT_APPLICABLE) != noDifferenceChecks.contains(i)) error = true;
        for (int i : noDifferenceChecks) if (i < 0 || i >= applicableChecks.size()) error = true;
        if (applicableChecks.contains(Status.FAIL)) return new Decision(CompleteAnswerStatus.FAIL, error);
        if (error) return new Decision(CompleteAnswerStatus.ERROR, true);
        if (!applicabilityCompleted || applicableChecks.contains(Status.SKIP) || !applicableChecks.contains(Status.PASS))
            return new Decision(CompleteAnswerStatus.NEEDS_REVIEW, false);
        return new Decision(CompleteAnswerStatus.PASS, false);
    }
}
