package com.agent.platform.common;

public class RetiredBusinessException extends IllegalArgumentException {
    public RetiredBusinessException() { super(BusinessRetirementPolicy.MESSAGE); }
}
