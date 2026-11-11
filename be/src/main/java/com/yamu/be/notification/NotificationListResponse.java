package com.yamu.be.notification;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yamu.be.global.common.PageResponse;
import java.time.LocalDateTime;

/**
 * 알림 목록(노션 API 명세서 V2 「알림 목록 조회」, Task 9.3.1). 안 읽은 수 아래에 공통 페이지 칸(items·page·size…)이 펼쳐진다.
 * 앱 {@code MyModels.kt}의 {@code NotificationsDto}가 읽는 모양이다.
 *
 * @param unreadCount 필터와 상관없는 내 안 읽은 알림 수
 */
public record NotificationListResponse(
        long unreadCount,
        @JsonUnwrapped PageResponse<Item> list) {

    /**
     * 알림 한 건. 제목·내용은 만들 때의 값으로 고정이다(이후 취소로 숫자가 바뀌어도 고치지 않는다).
     *
     * @param type      {@link NotificationType} 이름
     * @param refType   누르면 갈 대상의 종류(예: {@code PAYMENT_TRANSACTION}, {@code USER_CARD}, {@code CARD_BILL}). 없으면 null
     * @param refId     대상 번호. refType이 null이면 null. 대상이 아직 내 것인지는 대상 API가 다시 확인한다
     * @param isRead    읽었으면 true(readAt이 있으면 true)
     * @param readAt    UTC 처음 읽은 시각. 안 읽었으면 null
     * @param createdAt UTC 만든 시각
     */
    public record Item(long notificationId, String type, String title, String body, String refType, Long refId,
            boolean isRead, LocalDateTime readAt, LocalDateTime createdAt) {}
}
