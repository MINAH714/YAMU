package com.yamu.be.global.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 문장({@code %m})에 들어간 휴대폰·카드·계좌 번호를 끝 4자리만 남기고 가린다(S15P31E103-370).
 *
 * <p>로그에는 원래 ID·예외 이름만 남기고, 금융망 요청 record는 {@code toString}을 가린다. 이 변환기는 누가 실수로 번호를 넣었을 때
 * 운영 로그(Loki)에 그대로 쌓이지 않게 하는 마지막 안전망이다. {@code logback-spring.xml}이 {@code %m}·{@code %msg}·{@code %message}를
 * 이 변환기로 바꾼다.
 *
 * <ul>
 *   <li>휴대폰: {@code 010-1234-5678}·{@code 01012345678} → {@code 010-****-5678}</li>
 *   <li>15~16자리 숫자(붙임·하이픈·공백): 카드·계좌·결제 코드 → {@code ************1234}</li>
 * </ul>
 *
 * <p>앞뒤가 숫자인 경우는 건드리지 않아 14자리 이하(시각 {@code yyyyMMddHHmmss})·17자리 이상(거래 고유번호)은 그대로다.
 * 한계: 예외 스택({@code %ex})의 메시지, 이름·생년월일처럼 모양만으로 알 수 없는 값은 가리지 못한다.
 */
public class PersonalDataMaskingConverter extends MessageConverter {

    private static final Pattern PHONE = Pattern.compile("(?<![\\d-])(01[016789])-?(\\d{3,4})-?(\\d{4})(?![\\d-])");
    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<![\\d-])(?:\\d[- ]?){11,12}(\\d{4})(?![\\d-])");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = replace(LONG_NUMBER, message, m -> "*".repeat(m.group().replaceAll("[- ]", "").length() - 4)
                + m.group(1));
        return replace(PHONE, masked, m -> m.group(1) + "-****-" + m.group(3));
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> to) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            matcher.appendReplacement(out, Matcher.quoteReplacement(to.apply(matcher)));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }
}
