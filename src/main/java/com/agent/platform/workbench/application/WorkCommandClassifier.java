package com.agent.platform.workbench.application;

public interface WorkCommandClassifier {
    CommandClassifierResult classify(CommandClassificationRequest request);
}

