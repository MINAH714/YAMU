package com.yamu.be.global.common;

import java.util.List;

/**
 * 목록 API의 공통 페이지 형식(API 명세서 1장 「목록(페이지네이션)」). {@code page}는 1부터, {@code size}는 기본 20·최대 100이다.
 *
 * <pre>
 * { "items": [], "page": 1, "size": 20, "totalElements": 57, "totalPages": 3, "hasNext": true }
 * </pre>
 *
 * 합계처럼 목록 옆에 칸이 더 있는 응답은 이 레코드를 {@code @JsonUnwrapped}로 펼쳐 같은 높이에 둔다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** @param totalElements 필터를 적용한 전체 개수(이 페이지만이 아니다) */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(items, page, size, totalElements, totalPages, page < totalPages);
    }

    /** 이 페이지 앞에서 건너뛸 행 수. */
    public static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }
}
