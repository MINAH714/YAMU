package com.yamu.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 연결 풀이 열리기 전에 JVM 시간대가 UTC로 바뀌는지 확인한다(S15P31E103-179).
 * 늦게 바꾸면 KST로 만든 연결이 DATE를 KST 자정으로 읽어 UTC 날짜가 하루 이르게 나온다.
 *
 * <p>같은 JVM에서 다른 테스트가 먼저 시간대를 바꿔 두면 이 테스트만으로는 잡히지 않는다.
 * KST PC에서 이 클래스만 실행하면 확인된다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-timezone-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UtcTimeZoneTest extends MySqlTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void JVM_기본_시간대는_UTC다() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }

    @Test
    void DB의_DATE를_저장된_날짜_그대로_읽는다() {
        // java.sql.Date는 연결이 저장해 둔 시간대의 자정으로 만들어지고, toLocalDate()는 JVM 시간대로 날짜를 뽑는다.
        Date date = jdbc.queryForObject("SELECT DATE '2026-10-29'", Date.class);

        assertThat(date.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 29));
    }
}
