package com.yamu.be.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notification} 표를 읽고 읽음 시각을 쓴다. 모든 SQL에 사용자 번호를 조건으로 넣어 남의 알림을 읽거나 바꾸지 않는다.
 *
 * <p>읽음 처리는 {@code read_at IS NULL} 조건을 붙인 UPDATE 한 번이다. 같은 알림을 두 요청이 동시에 읽음 처리해도
 * 먼저 커밋된 쪽의 시각만 남고, 이미 읽은 알림의 처음 읽은 시각은 바뀌지 않는다.
 *
 * <p>알림을 만드는 쪽(엔티티·중복 방지 키)은 Task 9.3.2(S15P31E103-377)에서 붙인다.
 */
@Repository
class NotificationReader {

    private static final String FILTER = """
             where n.user_id = :userId
               and (:allTypes = true or n.notification_type in (:types))
               and (:unreadOnly = false or n.read_at is null)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    NotificationReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param types      비어 있으면 모든 유형
     * @param unreadOnly true면 안 읽은 알림만
     */
    record Filter(long userId, Collection<NotificationType> types, boolean unreadOnly) {

        MapSqlParameterSource params() {
            // IN ()은 SQL 오류라 유형이 없을 때도 자리값 하나를 넣고 allTypes로 조건을 끈다.
            List<String> names = types.isEmpty() ? List.of("") : types.stream().map(Enum::name).toList();
            return new MapSqlParameterSource("userId", userId)
                    .addValue("allTypes", types.isEmpty())
                    .addValue("types", names)
                    .addValue("unreadOnly", unreadOnly);
        }
    }

    /** 최신순(생성 시각, 같으면 번호 큰 것 먼저) 한 페이지. */
    List<Row> page(Filter filter, long offset, int size) {
        return jdbc.query("""
                select n.notification_id, n.notification_type, n.title, n.body, n.target_type, n.target_id,
                       n.read_at, n.created_at
                  from notification n
                """ + FILTER + """
                 order by n.created_at desc, n.notification_id desc
                 limit :size offset :offset
                """, filter.params().addValue("size", size).addValue("offset", offset), NotificationReader::row);
    }

    /** 필터를 적용한 전체 개수. */
    long count(Filter filter) {
        Long count = jdbc.queryForObject("select count(*) from notification n" + FILTER, filter.params(), Long.class);
        return count == null ? 0 : count;
    }

    /** 필터와 상관없는 안 읽은 알림 수(앱의 종 배지). */
    long unreadCount(long userId) {
        Long count = jdbc.queryForObject(
                "select count(*) from notification where user_id = :userId and read_at is null",
                new MapSqlParameterSource("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    /** 안 읽은 내 알림이면 읽음 시각을 쓰고 1, 이미 읽었거나 없거나 남의 알림이면 0을 돌려준다. */
    int markRead(long userId, long notificationId, LocalDateTime now) {
        return jdbc.update("""
                update notification set read_at = :now, updated_at = :now
                 where notification_id = :id and user_id = :userId and read_at is null
                """, new MapSqlParameterSource("now", now).addValue("id", notificationId).addValue("userId", userId));
    }

    /** 내 알림의 읽음 상태. 알림이 없거나 남의 알림이면 empty다. */
    Optional<ReadState> readState(long userId, long notificationId) {
        List<ReadState> rows = jdbc.query("""
                select read_at from notification where notification_id = :id and user_id = :userId
                """, new MapSqlParameterSource("id", notificationId).addValue("userId", userId),
                (rs, i) -> new ReadState(rs.getObject("read_at", LocalDateTime.class)));
        return rows.stream().findFirst();
    }

    /** 내 안 읽은 알림을 모두 읽음으로 바꾸고 바꾼 건수를 돌려준다. */
    int markAllRead(long userId, LocalDateTime now) {
        return jdbc.update("""
                update notification set read_at = :now, updated_at = :now
                 where user_id = :userId and read_at is null
                """, new MapSqlParameterSource("now", now).addValue("userId", userId));
    }

    /** @param readAt UTC 읽은 시각. 안 읽었으면 null */
    record ReadState(LocalDateTime readAt) {}

    record Row(long notificationId, String type, String title, String body, String targetType, Long targetId,
            LocalDateTime readAt, LocalDateTime createdAt) {}

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getLong("notification_id"), rs.getString("notification_type"), rs.getString("title"),
                rs.getString("body"), rs.getString("target_type"), rs.getObject("target_id", Long.class),
                rs.getObject("read_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class));
    }
}
