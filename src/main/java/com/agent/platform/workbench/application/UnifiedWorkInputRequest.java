package com.agent.platform.workbench.application;

import com.agent.platform.workbench.model.ClassifierType;
import com.agent.platform.workbench.model.WorkCommandType;

public record UnifiedWorkInputRequest(
        String inputId,
        String clientInputId,
        String conversationId,
        String content,
        ClassifierType classifierType,
        WorkCommandType explicitCommand,
        String explicitGoalText
) {
    public UnifiedWorkInputRequest {
        inputId = require(inputId, "inputId");
        clientInputId = require(clientInputId, "clientInputId");
        conversationId = require(conversationId, "conversationId");
        content = require(content, "content");
        classifierType = classifierType == null ? ClassifierType.MODEL : classifierType;
        explicitGoalText = explicitGoalText == null ? "" : explicitGoalText.trim();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
