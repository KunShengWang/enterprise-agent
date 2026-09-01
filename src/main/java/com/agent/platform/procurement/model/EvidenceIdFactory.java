package com.agent.platform.procurement.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 统一生成可跨 Provider/Decision 路径复现的 Evidence ID。 */
public final class EvidenceIdFactory {
    private EvidenceIdFactory() { }

    public static String id(String supplierId, String evidenceType, String source,
                             String sourceRecordId, String sourceSnapshot, String sourceAsOf,
                             String sourceDigest, String fact) {
        String canonical = String.join("|", safe(supplierId), safe(evidenceType), safe(source),
                safe(sourceRecordId), safe(sourceSnapshot), safe(sourceAsOf), safe(sourceDigest), safe(fact));
        try {
            return "evidence-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        }
        catch (Exception exception) { throw new IllegalStateException("failed to create procurement evidence id", exception); }
    }

    public static String digest(String source, String sourceRecordId, String sourceSnapshot, String fact) {
        String canonical = String.join("|", safe(source), safe(sourceRecordId), safe(sourceSnapshot), safe(fact));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception) { throw new IllegalStateException("failed to create procurement source digest", exception); }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
