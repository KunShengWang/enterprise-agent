package com.agent.platform.workbench.application;

final class StructuredJsonExtractor {

    private StructuredJsonExtractor() {
    }

    static String extractObject(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("structured model output is not a JSON object");
        }
        return raw.substring(start, end + 1);
    }
}

