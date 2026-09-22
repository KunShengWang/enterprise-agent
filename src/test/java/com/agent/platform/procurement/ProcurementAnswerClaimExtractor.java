package com.agent.platform.procurement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static com.agent.platform.procurement.ProcurementAnswerEvaluation.*;

/** Deliberately finite grammar. Every non-punctuation remainder becomes an unresolved claim. */
public final class ProcurementAnswerClaimExtractor {
    public static final String VERSION = "procurement-claims-v1.1";
    private static final Pattern SEGMENT = Pattern.compile("[^，,；;。！!？?\\r\\n]+");
    private static final Pattern SUBJECT = Pattern.compile("^(?:(推荐(?:供应商)?|备选(?:供应商)?)\\s*)?(Supplier [A-Za-z][A-Za-z0-9_-]*)(?=$|[\\s：:的])(?:\\s*[：:的]\\s*|\\s*)");
    private static final Pattern MONEY = Pattern.compile("^(单价|总价)\\s*(?:为|是)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(万)?\\s*(元|人民币|CNY|USD|美元|EUR|欧元)?$");
    private static final Pattern NUMBER = Pattern.compile("^(采购数量|数量|采购|报价交期|交期|用户要求期限)\\s*(?:为|是)?\\s*([0-9]+)\\s*(台|天)$");
    private static final Pattern CURRENCY = Pattern.compile("^币种\\s*(?:为|是)?\\s*(CNY|人民币|USD|美元|EUR|欧元)$");
    private static final Pattern QUALIFIER = Pattern.compile("如果|若|假如|假设|将|计划|拟|预计|可能|大约|约|不|未|没有|曾|之前|据说|或|只有|只要|除非|否则|是否|能否|可否|难道|吗|么|非|否认|取消");
    private static final Pattern CONDITION = Pattern.compile("如果|若|假如|假设|只有|只要|除非|否则|前提|条件|仅在|只在");

    public List<AnswerClaim> extract(String answer) {
        if (answer == null || answer.isBlank()) return List.of(new AnswerClaim("", 0, 0, "", "UNRESOLVED",
                null, "", "", "UNKNOWN", VERSION, "EMPTY_ANSWER"));
        List<AnswerClaim> claims = new ArrayList<>();
        // Conditional scope across sentence boundaries is outside this grammar. Abstain conservatively.
        boolean conditionalContext = CONDITION.matcher(answer).find();
        var segments = SEGMENT.matcher(answer);
        String subject = "", modality = "ASSERTED";
        int previousEnd = 0;
        while (segments.find()) {
            String gap = answer.substring(previousEnd, segments.start());
            if (gap.matches("(?s).*[；;。！!？?\\r\\n].*")) { subject = ""; modality = "ASSERTED"; }
            previousEnd = segments.end();
            int start = segments.start(), end = segments.end();
            while (start < end && Character.isWhitespace(answer.charAt(start))) start++;
            while (end > start && Character.isWhitespace(answer.charAt(end - 1))) end--;
            if (start == end) continue;
            String text = answer.substring(start, end);
            int sentenceStart = start, sentenceEnd = end;
            while (sentenceStart > 0 && "；;。！!？?\r\n".indexOf(answer.charAt(sentenceStart - 1)) < 0) sentenceStart--;
            while (sentenceEnd < answer.length() && "；;。！!？?\r\n".indexOf(answer.charAt(sentenceEnd)) < 0) sentenceEnd++;
            if (conditionalContext || QUALIFIER.matcher(answer.substring(sentenceStart, sentenceEnd)).find()
                    || sentenceEnd < answer.length() && "？?".indexOf(answer.charAt(sentenceEnd)) >= 0) modality = "QUALIFIED";
            // A qualifier applies to the whole comma-connected sentence; never turn a suffix into an event.
            if (!"ASSERTED".equals(modality)) {
                claims.add(claim(answer, start, end, "", "UNRESOLVED", null, "", "", modality, "NEGATION_CONDITION_TENSE_OR_HEDGE"));
                subject = "";
                continue;
            }
            var prefix = SUBJECT.matcher(text);
            boolean explicitSubject = prefix.find();
            if (explicitSubject) {
                String remainder = text.substring(prefix.end());
                // Do not assert a recommendation from a prefix of an unsupported sentence.
                if (!remainder.isEmpty() && !MONEY.matcher(remainder).matches()
                        && !NUMBER.matcher(remainder).matches() && !CURRENCY.matcher(remainder).matches()) {
                    claims.add(claim(answer, start, end, "", "UNRESOLVED", null, "", "", modality, "UNSUPPORTED_SUBJECT_CLAUSE"));
                    subject = "";
                    continue;
                }
                subject = "supplier-" + prefix.group(2).substring("Supplier ".length()).toLowerCase(java.util.Locale.ROOT);
                if (prefix.group(1) != null) claims.add(claim(answer, start, start + prefix.end(), subject,
                        prefix.group(1).startsWith("备选") ? "ALTERNATIVE" : "RECOMMENDATION", null, "", "", modality, ""));
                int consumed = prefix.end();
                start += consumed;
                text = answer.substring(start, end);
                if (text.isEmpty()) {
                    if (prefix.group(1) == null) claims.add(claim(answer, segments.start(), end, subject, "UNRESOLVED", null, "", "", modality, "BARE_SUBJECT"));
                    continue;
                }
            }
            var money = MONEY.matcher(text);
            var number = NUMBER.matcher(text);
            var currencyOnly = CURRENCY.matcher(text);
            if (money.matches()) {
                BigDecimal value = new BigDecimal(money.group(2));
                if (money.group(3) != null) value = value.multiply(new BigDecimal("10000"));
                String currency = switch (money.group(4) == null ? "" : money.group(4)) {
                    case "元", "人民币", "CNY" -> "CNY";
                    case "美元", "USD" -> "USD";
                    case "欧元", "EUR" -> "EUR";
                    default -> "CASE_CURRENCY";
                };
                claims.add(claim(answer, start, end, subject, money.group(1).equals("单价") ? "UNIT_PRICE" : "TOTAL_PRICE",
                        value.stripTrailingZeros(), "MONEY", currency, modality, subject.isEmpty() ? "AMBIGUOUS_SUBJECT" : ""));
            } else if (currencyOnly.matches()) {
                String currency = switch (currencyOnly.group(1)) {
                    case "人民币", "CNY" -> "CNY";
                    case "美元", "USD" -> "USD";
                    default -> "EUR";
                };
                claims.add(claim(answer, start, end, subject, "CURRENCY", null, "", currency, modality,
                        subject.isEmpty() ? "AMBIGUOUS_SUBJECT" : ""));
            } else if (number.matches()) {
                String property = switch (number.group(1)) {
                    case "报价交期", "交期" -> "LEAD_TIME";
                    case "用户要求期限" -> "REQUIRED_DELIVERY";
                    default -> "QUANTITY";
                };
                boolean quantity = property.equals("QUANTITY");
                String bound = property.equals("LEAD_TIME") ? subject : "case";
                boolean ambiguousCaseSubject = explicitSubject && bound.equals("case");
                if (ambiguousCaseSubject) bound = subject;
                String reason = !number.group(3).equals(quantity ? "台" : "天") ? "WRONG_UNIT"
                        : ambiguousCaseSubject ? "AMBIGUOUS_CASE_VS_SUPPLIER_SUBJECT" : bound.isEmpty() ? "AMBIGUOUS_SUBJECT" : "";
                claims.add(claim(answer, start, end, bound, property, new BigDecimal(number.group(2)), number.group(3), "", modality, reason));
            } else {
                String state = switch (text) {
                    case "本阶段仅完成只读推荐", "仅完成只读推荐", "只读推荐" -> "READ_ONLY";
                    case "待审批", "等待审批", "RFQ 待审批" -> "WAITING_APPROVAL";
                    case "RFQ 已创建", "RFQ已创建", "RFQ 创建成功", "已创建 RFQ" -> "RFQ_CREATED";
                    case "执行失败", "RFQ 创建失败" -> "EXECUTION_FAILED";
                    default -> "UNRESOLVED";
                };
                claims.add(claim(answer, start, end, "execution", state, null, "", "", modality,
                        state.equals("UNRESOLVED") ? "UNSUPPORTED_EXPRESSION" : ""));
                // An unparsed clause may have changed the subject. Do not carry a stale binding forward.
                subject = "";
            }
        }
        if (claims.isEmpty()) claims.add(new AnswerClaim(answer, 0, answer.length(), "", "UNRESOLVED", null,
                "", "", "UNKNOWN", VERSION, "NO_CLAIMS"));
        return List.copyOf(claims);
    }

    private AnswerClaim claim(String answer, int start, int end, String subject, String property,
                              BigDecimal number, String unit, String currency, String modality, String reason) {
        return new AnswerClaim(answer.substring(start, end), start, end, subject, property, number, unit, currency,
                modality, VERSION, reason);
    }
}
