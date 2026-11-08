package com.yamu.be.cashflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 정수 칸(금액·ID·반복일)에 소수가 오면 소수점 아래를 버리지 않고 거절하는 JSON 읽기(S15P31E103-318).
 *
 * <p>Jackson 기본값은 {@code 500000.9}를 정수 칸에 넣을 때 500000으로 잘라, 사용자가 보낸 것과 다른 금액·계좌·가맹점으로
 * 저장하거나 계산했다. 이 읽기는 값이 정수인 소수({@code 1000.0}, {@code 1e3})만 받고 나머지는 형식 오류로 던져,
 * 전역 예외 처리가 400 INVALID_REQUEST로 돌려준다. 가상 구매 금액(BigDecimal)과 같은 기준이다.
 * 정수·문자열·null은 Jackson 기본 읽기 그대로다. 전역 설정은 다른 팀 API도 바뀌어 건드리지 않는다.
 */
public final class IntegralNumberDeserializers {

    private IntegralNumberDeserializers() {
    }

    /** Long 칸용. 목록이면 {@code @JsonDeserialize(contentUsing = LongValue.class)}로 붙인다. */
    public static final class LongValue extends StdDeserializer<Long> {
        /** 소수가 아닌 값(정수·문자열·null)은 Jackson 기본 Long 읽기에 맡긴다. */
        private static final NumberDeserializers.LongDeserializer DEFAULT =
                new NumberDeserializers.LongDeserializer(Long.class, null);

        public LongValue() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                return DEFAULT.deserialize(p, ctxt);
            }
            try {
                return integral(p).longValueExact();
            } catch (ArithmeticException e) {
                throw InvalidFormatException.from(p, "정수가 아니거나 범위를 넘었습니다", p.getText(), Long.class);
            }
        }
    }

    /** Integer 칸용. */
    public static final class IntegerValue extends StdDeserializer<Integer> {
        /** 소수가 아닌 값은 Jackson 기본 Integer 읽기에 맡긴다. */
        private static final NumberDeserializers.IntegerDeserializer DEFAULT =
                new NumberDeserializers.IntegerDeserializer(Integer.class, null);

        public IntegerValue() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                return DEFAULT.deserialize(p, ctxt);
            }
            try {
                return integral(p).intValueExact();
            } catch (ArithmeticException e) {
                throw InvalidFormatException.from(p, "정수가 아니거나 범위를 넘었습니다", p.getText(), Integer.class);
            }
        }
    }

    /**
     * 소수 표기 값에서 0인 소수점 아래를 떼어 낸다. 소수점 아래가 남거나 너무 크면 ArithmeticException.
     * 아주 큰 지수({@code 1e999999999})를 정수로 펼치지 않도록 자릿수를 먼저 본다.
     */
    private static BigDecimal integral(JsonParser p) throws IOException {
        BigDecimal stripped = p.getDecimalValue().stripTrailingZeros();
        if (stripped.scale() > 0 || stripped.precision() - stripped.scale() > 19) {
            throw new ArithmeticException("not an integral long");
        }
        return stripped;
    }
}
