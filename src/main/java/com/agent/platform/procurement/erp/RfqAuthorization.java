package com.agent.platform.procurement.erp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.ERP_AUTH_ERROR;

/** Backend-only proof emitted after the existing approval/Case checks; never a model credential. */
public final class RfqAuthorization {
    private final byte[] key;
    static RfqAuthorization fromEnvironment() {
        return new RfqAuthorization(System.getenv("ERPNEXT_RFQ_SIGNING_SECRET"));
    }
    public RfqAuthorization(String secret) {
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("RFQ signing secret must contain at least 32 characters");
        key=secret.getBytes(StandardCharsets.UTF_8);
    }
    public String sign(ProcurementErpAdapter.RfqRequest request,long expiresAt) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((expiresAt+"\n"+new ObjectMapper().writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException("RFQ signing unavailable"); }
    }
    public void verify(ProcurementErpAdapter.RfqRequest request,long expiresAt,String signature) {
        long now=Instant.now().getEpochSecond();
        if(expiresAt<=now || expiresAt>now+300 || signature==null || !MessageDigest.isEqual(
                sign(request,expiresAt).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))
            throw new ProcurementErpException(ERP_AUTH_ERROR);
    }
}
