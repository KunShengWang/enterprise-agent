package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.ReviewerAssessmentDraft;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Parses the Reviewer's non-authoritative output without weakening the authoritative Java validator. */
@Component
public class ReviewerAssessmentDraftParser {

    private static final String SCHEMA = "reviewer-assessment-v1";

    private final ObjectMapper objectMapper;

    public ReviewerAssessmentDraftParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReviewerAssessmentDraft parse(String answer) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(answer));
            // Some models add a schema-named presentation wrapper. Unwrap only that known key;
            // singular or otherwise malformed domain fields remain invalid and trigger fallback.
            JsonNode candidate = root.has(SCHEMA) && root.get(SCHEMA).isObject()
                    ? root.get(SCHEMA)
                    : root;
            ReviewerAssessmentDraft draft = objectMapper.treeToValue(candidate, ReviewerAssessmentDraft.class);
            return SCHEMA.equals(draft.schemaVersion()) ? draft : invalid();
        }
        catch (RuntimeException exception) {
            return invalid();
        }
    }

    private ReviewerAssessmentDraft invalid() {
        return new ReviewerAssessmentDraft("", List.of(), List.of(), List.of(), null, List.of());
    }

    private String extractJson(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            int closing = normalized.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                normalized = normalized.substring(firstLine + 1, closing).trim();
            }
        }
        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("reviewer output does not contain a JSON object");
        }
        return normalized.substring(objectStart, objectEnd + 1);
    }
}
