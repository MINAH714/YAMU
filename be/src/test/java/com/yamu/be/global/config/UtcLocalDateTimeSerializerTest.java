package com.yamu.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class UtcLocalDateTimeSerializerTest {

    @Autowired
    ObjectMapper objectMapper;

    record Sample(LocalDateTime approvedAt, LocalDate billingDate) {
    }

    @Test
    void LocalDateTime은_끝에_Z를_붙여_내보낸다() throws Exception {
        String json = objectMapper.writeValueAsString(
                new Sample(LocalDateTime.of(2026, 10, 22, 5, 30), LocalDate.of(2026, 10, 25)));

        assertThat(json).isEqualTo("{\"approvedAt\":\"2026-10-22T05:30:00Z\",\"billingDate\":\"2026-10-25\"}");
    }

    @Test
    void 밀리초_마이크로초는_버리고_초까지만_내보낸다() throws Exception {
        assertThat(objectMapper.writeValueAsString(LocalDateTime.of(2026, 10, 22, 5, 30, 0, 123_000_000)))
                .isEqualTo("\"2026-10-22T05:30:00Z\"");
        assertThat(objectMapper.writeValueAsString(LocalDateTime.of(2026, 10, 22, 5, 30, 0, 123_456_000)))
                .isEqualTo("\"2026-10-22T05:30:00Z\"");
    }

    @Test
    void 요청의_밀리초가_붙은_Z_시각도_읽는다() throws Exception {
        // JS Date.toISOString()은 항상 밀리초를 붙인다(예: 2026-10-22T05:30:00.123Z).
        Sample sample = objectMapper.readValue(
                "{\"approvedAt\":\"2026-10-22T05:30:00.123Z\",\"billingDate\":\"2026-10-25\"}", Sample.class);

        assertThat(sample.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 5, 30, 0, 123_000_000));
    }

    @Test
    void 요청의_Z_붙은_시각은_UTC_LocalDateTime으로_읽는다() throws Exception {
        Sample sample = objectMapper.readValue(
                "{\"approvedAt\":\"2026-10-22T05:30:00Z\",\"billingDate\":\"2026-10-25\"}", Sample.class);

        assertThat(sample.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 5, 30));
    }
}
