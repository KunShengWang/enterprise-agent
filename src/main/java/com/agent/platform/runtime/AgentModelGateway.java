package com.agent.platform.runtime;

public interface AgentModelGateway {

    AgentModelTurn nextTurn(AgentModelRequest request);
}
