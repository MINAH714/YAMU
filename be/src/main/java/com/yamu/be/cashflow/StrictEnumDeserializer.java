package com.yamu.be.cashflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;

/**
 * enum 칸에 이름과 정확히 같은 JSON 문자열만 받는 읽기(S15P31E103-339).
 *
 * <p>Jackson 기본값은 숫자({@code 0})와 숫자 문자열({@code "0"})을 enum 순번으로 읽고 앞뒤 공백을 잘라 받아,
 * {@code "type": 0}이 INCOME, ISO 요일 번호 {@code "dayOfWeek": 1}(월요일)이 TUESDAY로 저장됐다. 이 읽기는 그 외 값을
 * 모두 형식 오류로 던져 전역 예외 처리가 400 INVALID_REQUEST로 돌려준다(가상 구매 {@code until}과 같은 기준).
 * null은 Jackson이 이 읽기를 부르지 않고 null로 둔다. 붙인 칸의 enum 타입은 {@link #createContextual}에서 알아낸다.
 * 전역 설정은 다른 팀 API도 바뀌어 건드리지 않는다.
 */
public final class StrictEnumDeserializer extends StdDeserializer<Enum<?>> implements ContextualDeserializer {

    /** 붙인 칸의 enum 타입. {@code @JsonDeserialize}가 만든 처음 객체는 null이고 createContextual이 채운 사본을 쓴다. */
    private final Class<?> enumType;

    public StrictEnumDeserializer() {
        this(null);
    }

    private StrictEnumDeserializer(Class<?> enumType) {
        super(enumType == null ? Enum.class : enumType);
        this.enumType = enumType;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        JavaType type = property != null ? property.getType() : ctxt.getContextualType();
        if (type == null || !type.isEnumType()) {
            throw new IllegalStateException("StrictEnumDeserializer는 enum 칸에만 붙인다: " + type);
        }
        return new StrictEnumDeserializer(type.getRawClass());
    }

    @Override
    public Enum<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (Enum<?>) ctxt.handleUnexpectedToken(enumType, p);
        }
        String text = p.getText();
        for (Object constant : enumType.getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            if (value.name().equals(text)) {
                return value;
            }
        }
        throw InvalidFormatException.from(p, "enum 이름과 정확히 같아야 합니다", text, enumType);
    }
}
