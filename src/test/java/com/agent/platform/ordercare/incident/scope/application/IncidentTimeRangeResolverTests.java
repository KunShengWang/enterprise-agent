package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentTimeRangeResolverTests {

    private final OrderCareProperties properties = new OrderCareProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-21T04:00:00Z"), ZoneOffset.UTC);
    private final IncidentTimeRangeResolver resolver = new IncidentTimeRangeResolver(properties, clock);

    @Test
    void resolvesLastNightUsingJavaProductRules() {
        ResolvedIncidentTimeRange range = resolver.resolve("昨晚", "Asia/Shanghai");

        assertThat(range.startTime()).isEqualTo(Instant.parse("2026-07-20T10:00:00Z"));
        assertThat(range.endTime()).isEqualTo(Instant.parse("2026-07-20T22:00:00Z"));
        assertThat(range.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(range.defaultTimezoneUsed()).isFalse();
    }

    @Test
    void resolvesRecentHoursAndExplicitIsoRange() {
        assertThat(resolver.resolve("最近 3 小时", "Asia/Shanghai").startTime())
                .isEqualTo(Instant.parse("2026-07-21T01:00:00Z"));
        assertThat(resolver.resolve(
                "2026-07-20T18:00:00/2026-07-20T23:00:00", "Asia/Shanghai").endTime())
                .isEqualTo(Instant.parse("2026-07-20T15:00:00Z"));
    }

    @Test
    void invalidTimezoneFallsBackExplicitly() {
        ResolvedIncidentTimeRange range = resolver.resolve("今天", "not/a-zone");

        assertThat(range.defaultTimezoneUsed()).isTrue();
        assertThat(range.timezone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void rejectsUnsupportedOrOversizedRanges() {
        assertThatThrownBy(() -> resolver.resolve("最近 25 小时", "Asia/Shanghai"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("某个晚上", "Asia/Shanghai"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
