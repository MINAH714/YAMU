package com.yamu.be.payment.bank;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 돈을 움직이는 SSAFY 금융망 API(노션 명세 「[SSAFY] 계좌 출금」「[SSAFY] 계좌 입금」「[SSAFY] 계좌 거래내역 조회」).
 * 체크카드 결제 출금(5.2.3), 청구 출금(5.4.3), 환불·환급 입금(5.3.3·5.4.4)이 이 클래스를 쓴다.
 *
 * <p>출금·입금은 자동으로 다시 보내지 않는다. 결과를 모르면 {@link TransferResult.Status#UNKNOWN}을 돌려주고,
 * 호출하는 쪽이 {@link #findByTag}로 거래내역에서 같은 태그를 찾아 결과를 확정한다(결제 상태·예외 처리 문서).
 * 노션 출금 명세의 "없으면 새 번호로 1회 재시도"는 이 클래스가 하지 않는다. 다시 보낼지는 호출하는 쪽이 정한다.
 *
 * <p>계좌번호·userKey는 로그에 남기지 않는다. 태그·금액·결과만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsafyTransferApi {

    static final String WITHDRAW_PATH = "/edu/demandDeposit/updateDemandDepositAccountWithdrawal";
    static final String DEPOSIT_PATH = "/edu/demandDeposit/updateDemandDepositAccountDeposit";
    static final String HISTORY_PATH = "/edu/demandDeposit/inquireTransactionHistoryList";

    /** 잔액 부족 */
    static final String INSUFFICIENT_BALANCE = "A1014";
    /** 계좌번호 오류(해지 등으로 계좌 없음) */
    static final String ACCOUNT_INVALID = "A1003";
    /**
     * 기관거래고유번호 중복. 같은 번호의 요청이 이미 처리됐을 수 있다.
     * 공통 클라이언트는 거절로 나누지만, 돈이 나갔을 수 있으므로 여기서는 결과 불명으로 본다.
     */
    static final String DUPLICATED_REQUEST_NO = "H1007";

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final FinApiClient finApiClient;
    private final Clock clock;

    /**
     * 출금·입금에 쓸 기관거래고유번호(20자리, 앞 8자리가 한국 날짜). 받은 번호를 PROCESSING 상태와 함께 DB에 먼저 저장한 뒤
     * {@link #withdraw}·{@link #deposit}에 넘긴다. 그래야 결과를 모를 때 그 날짜로 거래내역을 찾을 수 있다.
     * 채번에 실패하면 금융망 예외(NOT_SENT)가 난다.
     */
    public String newTransactionNo() {
        return finApiClient.newTransactionNo();
    }

    /**
     * 연결 계좌에서 출금한다.
     *
     * @param amount        출금액(원). 1원 이상
     * @param tag           결제({@code YAMU-P})나 청구({@code YAMU-B}) 태그
     * @param transactionNo {@link #newTransactionNo()}로 받아 미리 저장해 둔 번호
     */
    public TransferResult withdraw(String userKey, String accountNo, long amount, TransferTag tag,
            String transactionNo) {
        return transfer(WITHDRAW_PATH, BankDirection.WITHDRAWAL, userKey, accountNo, amount, tag, transactionNo);
    }

    /**
     * 연결 계좌에 입금한다.
     *
     * @param amount        입금액(원). 1원 이상
     * @param tag           환급({@code YAMU-R})이나 체크카드 환불({@code YAMU-C}) 태그
     * @param transactionNo {@link #newTransactionNo()}로 받아 미리 저장해 둔 번호
     */
    public TransferResult deposit(String userKey, String accountNo, long amount, TransferTag tag,
            String transactionNo) {
        return transfer(DEPOSIT_PATH, BankDirection.DEPOSIT, userKey, accountNo, amount, tag, transactionNo);
    }

    /**
     * 계좌 거래내역을 오래된 순으로 읽는다. 날짜는 한국 날짜이고 SSAFY 서버가 찍은 날짜 기준이다.
     * 실패하면 금융망 예외를 그대로 던진다(조회라서 결과 불명이어도 돈이 움직이지 않는다).
     *
     * @param direction 출금이나 입금만 보려면 지정한다. null이면 전체
     */
    public List<BankTransaction> history(String userKey, String accountNo, LocalDate from, LocalDate to,
            BankDirection direction) {
        return fetch(userKey, accountNo, from, to, direction).transactions();
    }

    /**
     * 결과를 모르는 출금·입금이 처리됐는지 거래내역에서 태그로 찾는다.
     * 요청한 날(번호 앞 8자리, 한국 날짜)의 전날부터 오늘까지 본다. 자정 직전 요청이 SSAFY에서 다음 날로 찍히거나
     * SSAFY 시계가 조금 늦어도 놓치지 않기 위해서다. 요청 직후에는 SSAFY가 아직 처리 중일 수 있으니 조금 뒤에 부른다.
     *
     * <p>조회 자체가 실패하면 금융망 예외를 던진다. 그때는 아무것도 확정하지 말고 다음에 다시 확인한다.
     *
     * @param amount        보냈던 금액. 태그가 같은데 금액이 다르면 {@link TagLookup.Result#AMOUNT_MISMATCH}
     * @param transactionNo 그 출금·입금에 썼던 기관거래고유번호
     */
    public TagLookup findByTag(String userKey, String accountNo, TransferTag tag, long amount, String transactionNo) {
        requireTag(tag);
        requirePositive(amount);
        LocalDate requestedOn = requestDateOf(transactionNo);
        LocalDate today = LocalDate.ofInstant(clock.instant(), KOREA);
        LocalDate to = today.isBefore(requestedOn) ? requestedOn : today;
        History history = fetch(userKey, accountNo, requestedOn.minusDays(1), to, tag.direction());

        String value = tag.value();
        List<BankTransaction> matches = history.transactions().stream()
                .filter(t -> t.direction() == tag.direction() && value.equals(t.summary()))
                .toList();
        TagLookup lookup;
        if (matches.isEmpty()) {
            if (!history.complete()) {
                // 목록이 잘려 왔으면 "없다"고 단정할 수 없다. 없다고 보면 실제로 나간 돈을 놓친다.
                log.warn("금융망 거래내역 tag={} 목록이 잘려 와서 없다고 판단하지 않음", tag);
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
            }
            lookup = new TagLookup(TagLookup.Result.NOT_FOUND, matches);
        } else if (matches.size() > 1) {
            lookup = new TagLookup(TagLookup.Result.DUPLICATED, matches);
        } else if (matches.get(0).amount() != amount) {
            lookup = new TagLookup(TagLookup.Result.AMOUNT_MISMATCH, matches);
        } else {
            lookup = new TagLookup(TagLookup.Result.FOUND, matches);
        }
        if (lookup.result() == TagLookup.Result.FOUND || lookup.result() == TagLookup.Result.NOT_FOUND) {
            log.info("금융망 거래내역 tag={} → {}", tag, lookup.result());
        } else {
            log.warn("금융망 거래내역 tag={} → {} ({}건) 운영 확인 필요", tag, lookup.result(), matches.size());
        }
        return lookup;
    }

    private TransferResult transfer(String path, BankDirection direction, String userKey, String accountNo,
            long amount, TransferTag tag, String transactionNo) {
        requireText(userKey, "userKey");
        requireText(accountNo, "accountNo");
        requirePositive(amount);
        requireTag(tag);
        if (tag.direction() != direction) {
            throw new IllegalArgumentException(tag + " 태그는 " + tag.direction() + "에 씁니다");
        }
        TransferResult result = send(path, userKey, accountNo, amount, tag, transactionNo);
        if (result.isSuccess()) {
            log.info("금융망 {} tag={} amount={} → SUCCESS", direction, tag, amount);
        } else {
            log.warn("금융망 {} tag={} amount={} → {} reason={} code={}", direction, tag, amount, result.status(),
                    result.rejectReason(), result.finCode());
        }
        return result;
    }

    private TransferResult send(String path, String userKey, String accountNo, long amount, TransferTag tag,
            String transactionNo) {
        TransferRec rec;
        try {
            rec = finApiClient.post(path, userKey, transactionNo,
                    new TransferRequest(accountNo, Long.toString(amount), tag.value()), TransferRec.class);
        } catch (FinApiException e) {
            return classify(e);
        }
        LocalDate date = parseDate(rec.transactionDate());
        if (date == null) {
            // 성공 코드였지만 거래일을 읽을 수 없다. 처리된 증거가 불완전하니 거래내역으로 확인하게 한다.
            return TransferResult.unknown(null);
        }
        return TransferResult.success(rec.transactionUniqueNo(), date);
    }

    private static TransferResult classify(FinApiException e) {
        return switch (e.getOutcome()) {
            case NOT_SENT -> TransferResult.notSent();
            case UNKNOWN -> TransferResult.unknown(e.getFinCode());
            case REJECTED -> rejected(e.getFinCode());
        };
    }

    private static TransferResult rejected(String finCode) {
        if (DUPLICATED_REQUEST_NO.equals(finCode)) {
            return TransferResult.unknown(finCode);
        }
        if (INSUFFICIENT_BALANCE.equals(finCode)) {
            return TransferResult.rejected(TransferResult.RejectReason.INSUFFICIENT_BALANCE, finCode);
        }
        if (ACCOUNT_INVALID.equals(finCode)) {
            return TransferResult.rejected(TransferResult.RejectReason.ACCOUNT_NOT_FOUND, finCode);
        }
        return TransferResult.rejected(TransferResult.RejectReason.OTHER, finCode);
    }

    private History fetch(String userKey, String accountNo, LocalDate from, LocalDate to, BankDirection direction) {
        requireText(userKey, "userKey");
        requireText(accountNo, "accountNo");
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("조회 기간이 잘못되었습니다: " + from + " ~ " + to);
        }
        String type = direction == null ? BankDirection.ALL_QUERY_CODE : direction.queryCode();
        HistoryRec rec = finApiClient.post(HISTORY_PATH, userKey,
                new HistoryRequest(accountNo, from.format(DATE), to.format(DATE), type, "ASC"), HistoryRec.class);

        List<BankTransaction> transactions = new ArrayList<>(rec.list().size());
        for (HistoryRow row : rec.list()) {
            transactions.add(toTransaction(row));
        }
        Long totalCount = parseCount(rec.totalCount());
        boolean complete = totalCount == null || totalCount <= transactions.size();
        if (!complete) {
            log.warn("금융망 거래내역 totalCount={}인데 {}건만 옴", totalCount, transactions.size());
        }
        return new History(transactions, complete);
    }

    /** 형식은 {@link HistoryRow}의 검사를 통과했다. 날짜가 달력에 없는 값(20261399)이면 응답이 깨진 것으로 본다. */
    private static BankTransaction toTransaction(HistoryRow row) {
        LocalDate date = parseDate(row.transactionDate());
        LocalTime time;
        try {
            time = LocalTime.parse(row.transactionTime(), TIME);
        } catch (DateTimeParseException e) {
            time = null;
        }
        if (date == null || time == null) {
            log.warn("금융망 거래내역 거래 일시를 읽지 못함 → 결과 불명");
            throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        }
        String summary = row.transactionSummary() == null ? "" : row.transactionSummary().strip();
        return new BankTransaction(row.transactionUniqueNo(), date, time,
                BankDirection.fromListCode(row.transactionType()), Long.parseLong(row.transactionBalance()),
                Long.parseLong(row.transactionAfterBalance()), summary);
    }

    /** 기관거래고유번호 앞 8자리는 번호를 만든 때의 한국 날짜다(스키마 사전 「금융망 요청 번호 채번 규칙」). */
    static LocalDate requestDateOf(String transactionNo) {
        if (transactionNo == null || !transactionNo.matches("\\d{20}")) {
            throw new IllegalArgumentException("기관거래고유번호는 20자리 숫자여야 합니다");
        }
        LocalDate date = parseDate(transactionNo.substring(0, 8));
        if (date == null) {
            throw new IllegalArgumentException("기관거래고유번호의 날짜가 잘못되었습니다");
        }
        return date;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long parseCount(String value) {
        if (value == null || !value.matches("\\d{1,18}")) {
            return null;
        }
        return Long.parseLong(value);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있습니다");
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 1원 이상이어야 합니다: " + amount);
        }
    }

    private static void requireTag(TransferTag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("태그가 없습니다");
        }
    }

    record TransferRequest(String accountNo, String transactionBalance, String transactionSummary) {
    }

    /** 거래 번호나 거래일이 빠진 성공 응답은 공통 클라이언트가 결과 불명으로 바꾼다. */
    record TransferRec(@NotBlank String transactionUniqueNo, @NotBlank String transactionDate) {
    }

    record HistoryRequest(String accountNo, String startDate, String endDate, String transactionType,
            String orderByType) {
    }

    /** 목록이 없거나 한 줄이라도 모양이 틀리면 공통 클라이언트가 결과 불명으로 바꾼다. 한 줄을 빼고 읽으면 그 줄의 태그를 놓친다. */
    record HistoryRec(String totalCount, @NotNull List<@NotNull @Valid HistoryRow> list) {
    }

    record HistoryRow(
            @NotBlank String transactionUniqueNo,
            @NotNull @Pattern(regexp = "\\d{8}") String transactionDate,
            @NotNull @Pattern(regexp = "\\d{6}") String transactionTime,
            @NotNull @Pattern(regexp = "[12]") String transactionType,
            @NotNull @Pattern(regexp = "\\d{1,18}") String transactionBalance,
            @NotNull @Pattern(regexp = "\\d{1,18}") String transactionAfterBalance,
            String transactionSummary) {
    }

    /** @param complete totalCount보다 적게 왔으면 false */
    private record History(List<BankTransaction> transactions, boolean complete) {
    }
}
