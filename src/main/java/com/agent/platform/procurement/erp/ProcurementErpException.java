package com.agent.platform.procurement.erp;

/** Safe boundary error. Never contains credentials, response bodies, or ERP tracebacks. */
public final class ProcurementErpException extends RuntimeException {
    public enum Code { ERP_AUTH_ERROR, ERP_NOT_FOUND, ERP_VALIDATION_ERROR, ERP_CONFLICT,
        ERP_TIMEOUT, ERP_UNAVAILABLE, ERP_RESPONSE_INVALID }
    private final Code code;
    public ProcurementErpException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
