package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class IncidentScopePolicy {

    private final OrderCareProperties properties;

    public IncidentScopePolicy(OrderCareProperties properties) {
        this.properties = properties;
    }

    public void validateCriteria(IncidentScopeCriteria criteria) {
        if (criteria == null || criteria.anomalyTypes().isEmpty()) {
            throw new IllegalArgumentException("a supported incident anomaly type is required");
        }
        boolean hasTime = criteria.startTime() != null && criteria.endTime() != null;
        boolean hasBusinessAnchor = !criteria.orderNos().isEmpty()
                || !criteria.deductNos().isEmpty() || !criteria.deadLetterIds().isEmpty();
        if (!hasTime && !hasBusinessAnchor) {
            throw new IllegalArgumentException("incident discovery requires a time or business anchor");
        }
        if (hasTime && (!criteria.startTime().isBefore(criteria.endTime())
                || Duration.between(criteria.startTime(), criteria.endTime()).compareTo(Duration.ofHours(24)) > 0)) {
            throw new IllegalArgumentException("incident discovery time range must not exceed 24 hours");
        }
    }

    public void validateCandidateCount(int count, boolean truncated) {
        if (count < 0 || count > properties.getIncidentScopeMaxCandidates() || truncated) {
            throw new IncidentScopeNarrowingRequiredException(
                    "incident scope exceeds the maximum candidate count; narrow the time range");
        }
    }
}
