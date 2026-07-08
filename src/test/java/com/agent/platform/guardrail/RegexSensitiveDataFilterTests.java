package com.agent.platform.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegexSensitiveDataFilterTests {

    @Test
    void shouldRedactIdCardBeforePhoneToAvoidPartialLeaks() {
        RegexSensitiveDataFilter filter = new RegexSensitiveDataFilter();

        SensitiveDataFilterResult result = filter.filter("手机号 13812345678，身份证 110101199003071234");

        assertThat(result.safeContent()).contains("[PHONE_REDACTED]");
        assertThat(result.safeContent()).contains("[ID_CARD_REDACTED]");
        assertThat(result.safeContent()).doesNotContain("13812345678");
        assertThat(result.safeContent()).doesNotContain("110101199003071234");
        assertThat(result.categories()).contains("phone", "id_card");
    }
}
