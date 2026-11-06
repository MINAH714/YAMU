package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 결제 설정 수정(노션 API 명세 V2 「결제 설정 수정」). 보낸 칸만 바꾼다.
 *
 * <p>{@code defaultUserCardId}는 "칸을 보내지 않음(그대로)"과 "{@code null}을 보냄(기본 카드 해제)"을 구분해야 해서 record가 아니라
 * 칸을 받았는지 기억하는 클래스로 둔다. record의 {@code Optional} 칸은 Jackson이 둘 다 빈 값으로 읽는다.
 */
public final class PaymentSettingsUpdateRequest {

    private Boolean autoSelect;
    private Long defaultUserCardId;
    private boolean defaultUserCardIdSent;

    public PaymentSettingsUpdateRequest() {
    }

    /** 결제 카드 자동 선택. 보내지 않으면 null(그대로) */
    public Boolean autoSelect() {
        return autoSelect;
    }

    /** {@code defaultUserCardId} 칸을 보냈는지. false면 기본 카드는 그대로 */
    public boolean changesDefaultCard() {
        return defaultUserCardIdSent;
    }

    /** 새 기본 카드. {@link #changesDefaultCard()}가 true인데 null이면 기본 카드 해제. 내 ACTIVE 카드만 고를 수 있다 */
    public Long defaultUserCardId() {
        return defaultUserCardId;
    }

    @JsonProperty("autoSelect")
    private void setAutoSelect(Boolean autoSelect) {
        this.autoSelect = autoSelect;
    }

    @JsonProperty("defaultUserCardId")
    private void setDefaultUserCardId(Long defaultUserCardId) {
        this.defaultUserCardId = defaultUserCardId;
        this.defaultUserCardIdSent = true;
    }
}
