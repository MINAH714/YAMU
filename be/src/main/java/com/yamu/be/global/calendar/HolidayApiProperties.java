package com.yamu.be.global.calendar;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공휴일 동기화 설정({@code yamu.holiday.*}). 공공데이터포털 「한국천문연구원_특일 정보」를 부른다.
 *
 * @param baseUrl        서비스 주소. 예: {@code https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService}
 * @param serviceKey     디코딩 키(인코딩 전 원래 값). 호출할 때 한 번만 인코딩한다. 인코딩 키(%가 든 값)를 넣어도 한 번 풀어서 쓴다.
 *                       운영은 환경변수 {@code DATA_GO_KR_SERVICE_KEY},
 *                       로컬은 application-local.yml. 비어 있으면 동기화를 건너뛴다. 로그·예외 메시지에 남기지 않는다
 * @param connectTimeout 연결 제한 시간
 * @param readTimeout    응답 제한 시간. 조회만 하므로 결과 불명을 따로 다루지 않고 실패로 본다
 * @param syncCron       매일 동기화 시각(한국 시간). 청구 확정(00:30) 뒤, 청구 출금(07:00) 전
 */
@ConfigurationProperties(prefix = "yamu.holiday")
public record HolidayApiProperties(String baseUrl, String serviceKey, Duration connectTimeout, Duration readTimeout,
        String syncCron) {

    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 로그에 설정을 찍어도 키가 나오지 않게 한다. */
    @Override
    public String toString() {
        return "HolidayApiProperties[baseUrl=" + baseUrl + ", serviceKey=" + (hasServiceKey() ? "***" : "")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", syncCron=" + syncCron + "]";
    }
}
