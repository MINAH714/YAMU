package com.yamu.be.notification;

import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 내 알림 목록과 읽음 처리(Task 9.3.1). 명세: 노션 API 명세서 V2 「알림 목록 조회」「알림 읽음 처리」.
 *
 * <p>없는 알림과 남의 알림은 똑같이 404 RESOURCE_NOT_FOUND다. 남의 알림 번호가 있는지 드러내지 않는다.
 * 이미 읽은 알림을 다시 읽음 처리하면 처음 읽은 시각을 그대로 돌려준다(연타·재시도에도 같은 결과).
 */
@Service
public class NotificationService {

    private final NotificationReader reader;
    private final Clock clock;

    NotificationService(NotificationReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * @param types 비었거나 null이면 모든 유형
     * @param page  1부터
     */
    @Transactional(readOnly = true)
    public NotificationListResponse list(long userId, Collection<NotificationType> types, boolean unreadOnly,
            int page, int size) {
        NotificationReader.Filter filter = new NotificationReader.Filter(userId, typesOf(types), unreadOnly);
        List<NotificationListResponse.Item> items = reader.page(filter, PageResponse.offset(page, size), size).stream()
                .map(row -> new NotificationListResponse.Item(row.notificationId(), row.type(), row.title(), row.body(),
                        row.targetType(), row.targetId(), row.readAt() != null, row.readAt(), row.createdAt()))
                .toList();
        return new NotificationListResponse(reader.unreadCount(userId),
                PageResponse.of(items, page, size, reader.count(filter)));
    }

    /** 내 안 읽은 알림 수. 홈 요약(Task 9.4.1)의 종 배지도 이 값을 쓴다. */
    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return reader.unreadCount(userId);
    }

    /** 알림 하나를 읽음으로 바꾼다. 이미 읽었으면 바꾸지 않고 처음 읽은 시각을 돌려준다. */
    @Transactional
    public ReadResponse read(long userId, long notificationId) {
        reader.markRead(userId, notificationId, now());
        NotificationReader.ReadState state = reader.readState(userId, notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new ReadResponse(notificationId, state.readAt());
    }

    /**
     * 내 안 읽은 알림을 모두 읽음으로 바꾼다. 앱이 마지막으로 본 알림 번호를 보내지 않으므로, 목록을 연 뒤 새로 생긴 알림도
     * 함께 읽음이 된다(한계).
     */
    @Transactional
    public ReadAllResponse readAll(long userId) {
        return new ReadAllResponse(reader.markAllRead(userId, now()));
    }

    /** @param readAt UTC 처음 읽은 시각 */
    public record ReadResponse(long notificationId, LocalDateTime readAt) {}

    /** @param updatedCount 이번에 읽음으로 바뀐 알림 수. 이미 모두 읽었으면 0 */
    public record ReadAllResponse(int updatedCount) {}

    /** DB 칸이 TIMESTAMP(6)이라 마이크로초까지 자른다. 자르지 않으면 응답과 저장값이 다를 수 있다. */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Collection<NotificationType> typesOf(Collection<NotificationType> types) {
        if (types == null) {
            return EnumSet.noneOf(NotificationType.class);
        }
        // "type=" 처럼 빈 값은 null 원소로 들어온다. 빈 값은 필터가 없는 것으로 본다.
        List<NotificationType> present = types.stream().filter(Objects::nonNull).toList();
        return present.isEmpty() ? EnumSet.noneOf(NotificationType.class) : EnumSet.copyOf(present);
    }
}
