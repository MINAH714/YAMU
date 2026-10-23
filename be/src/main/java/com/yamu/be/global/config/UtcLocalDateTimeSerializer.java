package com.yamu.be.global.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.springframework.boot.jackson.JsonComponent;

/**
 * 응답 JSON의 LocalDateTime 끝에 UTC 표시 'Z'를 붙인다. 예: {@code "2026-10-22T05:30:00Z"}
 *
 * <p>서버 기본 시간대가 UTC라 서버 안의 LocalDateTime은 모두 UTC 시각이다. 그런데 Jackson 기본 형식은
 * 시간대 없이 {@code "2026-10-22T05:30:00"}으로 내보내서, FE(JS {@code new Date})가 이를 기기 시각(KST)으로 읽고
 * 9시간 어긋나게 표시한다. 'Z'를 붙이면 FE가 UTC로 읽고 화면에서 알아서 한국 시각으로 바꾼다.
 *
 * <p>그래서 LocalDateTime에는 UTC 시각만 담는다. 결제일·청구일처럼 한국 날짜가 기준인 값은 LocalDate로 내보낸다.
 * 요청 JSON의 {@code "...Z"}는 Jackson 기본 동작대로 'Z'를 떼고 LocalDateTime으로 읽는다.
 */
@JsonComponent
public class UtcLocalDateTimeSerializer extends LocalDateTimeSerializer {

    private static final DateTimeFormatter UTC_FORMAT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendLiteral('Z')
            .toFormatter();

    public UtcLocalDateTimeSerializer() {
        super(UTC_FORMAT);
    }

    @Override
    public Class<LocalDateTime> handledType() {
        return LocalDateTime.class;
    }
}
