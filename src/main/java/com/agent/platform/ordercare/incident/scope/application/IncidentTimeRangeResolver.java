package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.config.OrderCareProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IncidentTimeRangeResolver {

    private static final Pattern RECENT_HOURS = Pattern.compile("(?:最近|过去)\\s*(\\d{1,2})\\s*小时");
    private static final Pattern EXPLICIT_RANGE = Pattern.compile("([^/]+)\\s*/\\s*([^/]+)");
    private static final Duration MAX_RANGE = Duration.ofHours(24);

    private final OrderCareProperties properties;
    private final Clock clock;

    @Autowired
    public IncidentTimeRangeResolver(OrderCareProperties properties) {
        this(properties, Clock.systemUTC());
    }

    IncidentTimeRangeResolver(OrderCareProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ResolvedIncidentTimeRange resolve(String expression, String requestedTimezone) {
        String normalized = expression == null ? "" : expression.trim();
        ZoneResolution zone = resolveZone(requestedTimezone);
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone.zoneId());
        ZonedDateTime start;
        ZonedDateTime end;
        if ("前天".equals(normalized)) {
            LocalDate today = now.toLocalDate();
            start = today.minusDays(2).atStartOfDay(zone.zoneId());
            end = today.minusDays(1).atStartOfDay(zone.zoneId());
        } else if ("昨晚".equals(normalized)) {
            LocalDate today = now.toLocalDate();
            start = ZonedDateTime.of(today.minusDays(1), LocalTime.of(18, 0), zone.zoneId());
            ZonedDateTime fixedEnd = ZonedDateTime.of(today, LocalTime.of(6, 0), zone.zoneId());
            end = fixedEnd.isAfter(now) ? now : fixedEnd;
        } else if ("今天".equals(normalized)) {
            start = now.toLocalDate().atStartOfDay(zone.zoneId());
            end = now;
        } else {
            Matcher recent = RECENT_HOURS.matcher(normalized);
            Matcher explicit = EXPLICIT_RANGE.matcher(normalized);
            if (recent.matches()) {
                int hours = Integer.parseInt(recent.group(1));
                if (hours < 1 || hours > 24) {
                    throw new IllegalArgumentException("recent hour range must be between 1 and 24");
                }
                end = now;
                start = now.minusHours(hours);
            } else if (explicit.matches()) {
                start = parseDateTime(explicit.group(1).trim(), zone.zoneId());
                end = parseDateTime(explicit.group(2).trim(), zone.zoneId());
            } else {
                throw new IllegalArgumentException("unsupported incident time expression");
            }
        }
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("incident discovery time range must be positive and not exceed 24 hours");
        }
        return new ResolvedIncidentTimeRange(
                start.toInstant(), end.toInstant(), zone.zoneId().getId(), zone.defaultUsed(), normalized);
    }

    private ZonedDateTime parseDateTime(String value, ZoneId zoneId) {
        try {
            return ZonedDateTime.parse(value).withZoneSameInstant(zoneId);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).atZone(zoneId);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("explicit incident time range must use ISO date-time", exception);
            }
        }
    }

    private ZoneResolution resolveZone(String requestedTimezone) {
        if (requestedTimezone != null && !requestedTimezone.isBlank()) {
            try {
                return new ZoneResolution(ZoneId.of(requestedTimezone.trim()), false);
            } catch (RuntimeException ignored) {
                // The configured server default is explicit in the returned preview.
            }
        }
        return new ZoneResolution(ZoneId.of(properties.getIncidentScopeDefaultTimezone()), true);
    }

    private record ZoneResolution(ZoneId zoneId, boolean defaultUsed) {
    }
}
