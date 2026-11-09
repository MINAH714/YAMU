package com.yamu.be.global.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.DayOfWeek;
import java.util.List;
import org.junit.jupiter.api.Test;

/** enum 칸이 숫자(순번)를 받지 않는 역직렬화기(S15P31E103-339). DB 없음. */
class StrictEnumDeserializerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    record Single(@JsonDeserialize(using = StrictEnumDeserializer.class) DayOfWeek day) {}

    record Many(@JsonDeserialize(contentUsing = StrictEnumDeserializer.class) List<DayOfWeek> days) {}

    @Test
    void 칸에_붙이면_이름은_읽고_숫자는_거절한다() throws Exception {
        assertThat(mapper.readValue("{\"day\": \"MONDAY\"}", Single.class).day()).isEqualTo(DayOfWeek.MONDAY);
        assertThatThrownBy(() -> mapper.readValue("{\"day\": 0}", Single.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void 묶음_원소에_붙여도_원소_enum으로_읽고_숫자는_거절한다() throws Exception {
        // !186 리뷰: 전에는 칸 타입(List)을 먼저 봐서 contentUsing으로 붙이면 IllegalStateException(첫 요청 500)이 났다.
        assertThat(mapper.readValue("{\"days\": [\"MONDAY\", \"FRIDAY\"]}", Many.class).days())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        assertThatThrownBy(() -> mapper.readValue("{\"days\": [0]}", Many.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
