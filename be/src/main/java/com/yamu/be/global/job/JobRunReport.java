package com.yamu.be.global.job;

import java.util.List;

/**
 * 정기 작업 한 번의 결과(「[운영] 정기 작업 수동 실행」 Response의 {@code summary}·{@code results}). 대상 하나에 한 줄이다.
 *
 * @param results 대상 순서대로. 대상이 없으면 빈 목록
 */
public record JobRunReport(List<Item> results) {

    public JobRunReport {
        results = List.copyOf(results);
    }

    public int targets() {
        return results.size();
    }

    public long count(Outcome outcome) {
        return results.stream().filter(item -> item.outcome() == outcome).count();
    }

    /** 집계용 결과 분류. 화면에 보이는 이름은 {@link Item#result()}다. */
    public enum Outcome {
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    /**
     * 대상 하나의 결과.
     *
     * @param refType          대상 종류(예: {@code CARD_BILL})
     * @param refId            대상 번호
     * @param userId           대상의 사용자
     * @param result           결과 이름(예: {@code CONFIRMED}·{@code PAID}·{@code SKIPPED}·{@code DRY_RUN}·{@code FAILED})
     * @param amount           처리한 금액(원). 없으면 null
     * @param finTransactionNo 금융망 거래 번호. 돈이 움직인 결과만
     * @param message          건너뛰거나 실패한 이유. 성공이면 null
     */
    public record Item(String refType, Long refId, Long userId, Outcome outcome, String result, Long amount,
            String finTransactionNo, String message) {

        public static Item succeeded(String refType, Long refId, Long userId, String result, Long amount,
                String finTransactionNo) {
            return new Item(refType, refId, userId, Outcome.SUCCEEDED, result, amount, finTransactionNo, null);
        }

        public static Item skipped(String refType, Long refId, Long userId, String message) {
            return new Item(refType, refId, userId, Outcome.SKIPPED, "SKIPPED", null, null, message);
        }

        public static Item failed(String refType, Long refId, Long userId, String message) {
            return new Item(refType, refId, userId, Outcome.FAILED, "FAILED", null, null, message);
        }

        public static Item dryRun(String refType, Long refId, Long userId, Long amount) {
            return new Item(refType, refId, userId, Outcome.SKIPPED, "DRY_RUN", amount, null, null);
        }
    }
}
