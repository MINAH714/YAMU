package com.yamu.be.user.dto;

import java.time.LocalDateTime;

/**
 * 보안 설정(명세 「보안 설정 수정」 응답).
 *
 * @param autoLockSeconds         앱을 떠난 뒤 다시 잠그기까지(초)
 * @param setupGraceSeconds       카드 등록·초기 설정 중 잠금 유예. 바꾸는 API가 없어 명세 기본값 300(임시)
 * @param barcodeAuthGraceSeconds 잠금 해제 뒤 결제 코드 열 때 다시 묻지 않는 시간. 명세 기본값 60(임시)
 * @param barcodeAuthMethod       결제 코드를 열 때 인증 방식
 * @param updatedAt               사용자 정보가 마지막으로 바뀐 시각(UTC). PIN 변경 등에도 바뀐다(임시)
 */
public record SecuritySettingsResponse(int autoLockSeconds, int setupGraceSeconds, int barcodeAuthGraceSeconds,
        String barcodeAuthMethod, LocalDateTime updatedAt) {
}
