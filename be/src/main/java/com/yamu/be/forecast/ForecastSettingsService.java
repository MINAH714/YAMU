package com.yamu.be.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.account.BankNames;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowItemRepository;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고예보 공통 설정을 조회·수정한다(명세 「잔고예보 설정 조회」「수정」, FR-041).
 *
 * <ul>
 *   <li>기준 계좌는 {@code linked_account.is_primary}를 읽어 보여 주기만 한다. 바꾸는 것은 계좌 수정 API(BE 1)다</li>
 *   <li>월급 항목은 시리즈의 첫 규칙 ID로 저장하고, 읽을 때 "이후 전부" 수정으로 이어진 가장 최근 규칙까지 따라간다.
 *       뒤 규칙은 다시 수정할 때 지워지고 새로 만들어지지만 첫 규칙은 남기 때문이다. 앞으로 남은 회차가 없으면 null이다</li>
 *   <li>수정은 보낸 필드만 바꾸고, 같은 사용자의 수정은 사용자 행 잠금으로 차례로 처리한다</li>
 * </ul>
 * 잔고예보 조회는 안전 잔액·기간 기준·기본 일수와 다음 월급일({@link #nextIncomeDate})을 읽는다. 생활비 상세는 같은 기간과 하루 목표를 읽는다.
 */
@Service
public class ForecastSettingsService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<String> FIELDS = Set.of("horizon", "defaultDays", "alertThreshold",
            "incomeCashflowItemId", "dailyLivingCostTarget", "includeCandidates", "conservativeLine");

    /** 남은 회차를 찾는 기간. 매월 반복이라 두 달이면 "이번만 삭제"한 회차가 하나 있어도 다음 회차가 보인다. */
    private static final int UPCOMING_DAYS = 62;

    private final ForecastSettingsStore store;
    private final CashflowItemRepository cashflowItems;
    private final CashflowService cashflows;
    private final LinkedAccountRepository accounts;
    private final Clock clock;

    ForecastSettingsService(ForecastSettingsStore store, CashflowItemRepository cashflowItems,
            CashflowService cashflows, LinkedAccountRepository accounts, Clock clock) {
        this.store = store;
        this.cashflowItems = cashflowItems;
        this.cashflows = cashflows;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** 로그인한 사용자의 설정. 저장한 적이 없으면 명세 기본값이다. */
    @Transactional(readOnly = true)
    public ForecastSettingsResponse get(long userId) {
        return response(userId, store.read(userId));
    }

    /**
     * 보낸 필드만 바꾸고 바뀐 설정 전체를 돌려준다. {@code incomeCashflowItemId}·{@code dailyLivingCostTarget}은 null이면 해제한다.
     *
     * @param body PATCH 본문 그대로. 보내지 않은 필드와 null을 구분하려고 JSON 트리로 받는다
     * @throws BusinessException 형식·범위 오류는 400 INVALID_REQUEST(fieldErrors), 없거나 남의 월급 항목은 404 RESOURCE_NOT_FOUND
     */
    @Transactional
    public ForecastSettingsResponse update(long userId, JsonNode body) {
        Patch patch = Patch.parse(body);
        ForecastSettings current = store.readForUpdate(userId);
        Long income = current.incomeCashflowItemId();
        if (patch.incomeSent()) {
            income = patch.income() == null ? null : first(userId, requireIncome(userId, patch.income())).getId();
        }
        ForecastSettings updated = new ForecastSettings(
                patch.horizon() != null ? patch.horizon() : current.horizon(),
                patch.defaultDays() != null ? patch.defaultDays() : current.defaultDays(),
                patch.alertThreshold() != null ? patch.alertThreshold() : current.alertThreshold(),
                income,
                patch.targetSent() ? patch.target() : current.dailyLivingCostTarget(),
                patch.includeCandidates() != null ? patch.includeCandidates() : current.includeCandidates(),
                patch.conservativeLine() != null ? patch.conservativeLine() : current.conservativeLine(),
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        store.write(userId, updated);
        return response(userId, updated);
    }

    private ForecastSettingsResponse response(long userId, ForecastSettings settings) {
        ForecastSettingsResponse.BaseAccount base = accounts.findByUserIdAndPrimaryTrue(userId).stream()
                .filter(LinkedAccount::isConnected)
                .findFirst()
                .map(account -> new ForecastSettingsResponse.BaseAccount(account.getId(),
                        BankNames.of(account.getBankCode()), mask(account.getAccountLast4())))
                .orElse(null);
        ForecastSettingsResponse.Income income = settings.incomeCashflowItemId() == null ? null
                : latest(userId, settings.incomeCashflowItemId())
                        .filter(item -> isUsableIncome(userId, item))
                        .map(item -> new ForecastSettingsResponse.Income(item.getId(), item.getTitle(), item.getAmount(),
                                item.getRecurrenceDayOfMonth() != null ? item.getRecurrenceDayOfMonth()
                                        : item.getScheduledDate().getDayOfMonth(),
                                item.getAccountId()))
                        .orElse(null);
        return new ForecastSettingsResponse(base == null ? null : base.accountId(), base, settings.horizon(),
                settings.defaultDays(), settings.alertThreshold(), income, settings.dailyLivingCostTarget(),
                settings.includeCandidates(), settings.conservativeLine(), settings.updatedAt());
    }

    /**
     * 설정한 월급 시리즈에서 after보다 뒤의 첫 입금일. 월급이 없거나 지금 월급으로 쓸 수 없거나(설정 조회에서 income이 null인 경우와 같음),
     * after 다음 날부터 두 달 안에 회차가 없으면 empty다. 날짜는 가계부 목록과 같은 계산(말일 보정, 이번만 옮김 반영)이다.
     *
     * @param incomeCashflowItemId 설정에 저장한 월급 시리즈의 첫 규칙 ID. null이면 월급 없음
     * @param after                이 날은 빼고 그 뒤부터 찾는다(한국 날짜)
     */
    @Transactional(readOnly = true)
    Optional<LocalDate> nextIncomeDate(long userId, Long incomeCashflowItemId, LocalDate after) {
        if (incomeCashflowItemId == null) {
            return Optional.empty();
        }
        Optional<CashflowItem> latest = latest(userId, incomeCashflowItemId);
        if (latest.isEmpty() || !isUsableIncome(userId, latest.get())) {
            return Optional.empty();
        }
        Set<Long> series = seriesIds(userId, incomeCashflowItemId);
        return cashflows.list(userId, after.plusDays(1), after.plusDays(UPCOMING_DAYS), CashflowItem.Direction.INCOME,
                        CashflowItem.Status.CONFIRMED, true).stream()
                .filter(entry -> series.contains(entry.item().id()))
                .map(CashflowService.Entry::date)
                .min(Comparator.naturalOrder());
    }

    /**
     * 저장한 규칙부터 "이후 전부" 수정으로 이어진 마지막 규칙까지의 ID. 수정 전 규칙에도 아직 남은 회차가 있을 수 있어 모두 본다.
     * 내 항목이 아니거나 없으면 빈 집합이다.
     */
    private Set<Long> seriesIds(long userId, long cashflowItemId) {
        Set<Long> ids = new HashSet<>();
        Optional<CashflowItem> current = cashflowItems.findById(cashflowItemId)
                .filter(item -> item.getUserId().equals(userId));
        while (current.isPresent() && ids.add(current.get().getId())) {
            List<CashflowItem> next = cashflowItems.findByPreviousCashflowItemIdAndUserId(current.get().getId(), userId);
            if (next.size() > 1) {
                throw new IllegalStateException("가계부 시리즈가 갈라져 있다 (cashflowItemId=" + current.get().getId() + ")");
            }
            current = next.stream().findFirst();
        }
        return ids;
    }

    /** 월급으로 지정할 항목. 이어진 최신 규칙이 월급으로 쓸 수 없으면 400, 없거나 남의 항목이면 404. */
    private CashflowItem requireIncome(long userId, long cashflowItemId) {
        CashflowItem item = latest(userId, cashflowItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!isUsableIncome(userId, item)) {
            throw invalid("incomeCashflowItemId", "앞으로 매월 들어오는 확정 수입 항목만 월급으로 고를 수 있습니다.");
        }
        return item;
    }

    /** 시리즈의 첫 규칙. 앞 규칙({@code previous_cashflow_item_id})을 끝까지 거슬러 올라간다. */
    private CashflowItem first(long userId, CashflowItem item) {
        CashflowItem current = item;
        Set<Long> visited = new HashSet<>();
        while (current.getPreviousCashflowItemId() != null) {
            if (!visited.add(current.getId())) {
                throw new IllegalStateException("가계부 시리즈가 순환한다 (cashflowItemId=" + current.getId() + ")");
            }
            current = cashflowItems.findById(current.getPreviousCashflowItemId())
                    .filter(previous -> previous.getUserId().equals(userId))
                    .orElseThrow(() -> new IllegalStateException("가계부 시리즈의 앞 규칙이 없다 (cashflowItemId="
                            + item.getId() + ")"));
        }
        return current;
    }

    /**
     * 저장한 항목에서 "이후 전부" 수정으로 이어진 마지막 규칙까지 따라간다. 시리즈는 한 줄로 이어지므로 뒤 규칙은 0개나 1개다.
     * 내 항목이 아니거나 없으면 empty다.
     */
    private Optional<CashflowItem> latest(long userId, long cashflowItemId) {
        Optional<CashflowItem> found = cashflowItems.findById(cashflowItemId)
                .filter(item -> item.getUserId().equals(userId));
        if (found.isEmpty()) {
            return found;
        }
        CashflowItem current = found.get();
        Set<Long> visited = new HashSet<>();
        while (visited.add(current.getId())) {
            List<CashflowItem> next = cashflowItems.findByPreviousCashflowItemIdAndUserId(current.getId(), userId);
            if (next.isEmpty()) {
                return Optional.of(current);
            }
            if (next.size() > 1) {
                throw new IllegalStateException("가계부 시리즈가 갈라져 있다 (cashflowItemId=" + current.getId() + ")");
            }
            current = next.get(0);
        }
        throw new IllegalStateException("가계부 시리즈가 순환한다 (cashflowItemId=" + current.getId() + ")");
    }

    /**
     * 앞으로 월급을 받을 수 있는 항목인지. 매월 반복하는 확정 수입이고, 한국 날짜 오늘(또는 첫 발생일)부터 두 달 안에
     * 실제 회차가 남아 있어야 한다. 종료일만 보면 "종료일은 오늘인데 이번 달 입금일은 지났다"를 놓친다.
     * 시작한 규칙을 전체 삭제하면 행이 남은 채 어제로 닫히는 것(가계부 삭제 규칙)도 여기서 걸린다.
     * 회차는 가계부 목록과 같은 계산(말일 보정, 이번만 삭제·옮김 반영)으로 찾는다.
     */
    private boolean isUsableIncome(long userId, CashflowItem item) {
        if (item.getDirection() != CashflowItem.Direction.INCOME
                || item.getRecurrenceType() != CashflowItem.RecurrenceType.MONTHLY
                || item.getStatus() != CashflowItem.Status.CONFIRMED
                || item.getItemKind() != CashflowItem.ItemKind.SCHEDULED) {
            return false;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), KOREA);
        LocalDate from = item.getScheduledDate().isAfter(today) ? item.getScheduledDate() : today;
        LocalDate to = from.plusDays(UPCOMING_DAYS - 1);
        if (item.getRecurrenceEndDate() != null) {
            if (item.getRecurrenceEndDate().isBefore(from)) {
                return false;
            }
            to = item.getRecurrenceEndDate().isBefore(to) ? item.getRecurrenceEndDate() : to;
        }
        return cashflows.list(userId, from, to, CashflowItem.Direction.INCOME, CashflowItem.Status.CONFIRMED, true)
                .stream().anyMatch(entry -> entry.item().id().equals(item.getId()));
    }

    private static String mask(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    /**
     * 검증한 PATCH 본문. null인 칸은 보내지 않은 것이다. 해제할 수 있는 두 칸은 보냈는지(…Sent)를 따로 둔다.
     */
    private record Patch(BalanceForecast.Until horizon, Integer defaultDays, Long alertThreshold, boolean incomeSent,
            Long income, boolean targetSent, Long target, Boolean includeCandidates, Boolean conservativeLine) {

        /** 형식·범위를 모두 검사해 틀린 필드를 한 번에 400으로 알린다. DB를 읽기 전에 부른다. */
        static Patch parse(JsonNode body) {
            List<FieldErrorResponse> errors = new ArrayList<>();
            if (body == null || !body.isObject() || body.isEmpty()) {
                throw invalid("body", "바꿀 설정을 하나 이상 보내 주세요.");
            }
            for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (name.equals("baseAccountId")) {
                    errors.add(new FieldErrorResponse(name, "기준 계좌는 계좌 정보 수정에서 바꿔 주세요."));
                } else if (!FIELDS.contains(name)) {
                    errors.add(new FieldErrorResponse(name, "바꿀 수 없는 설정입니다."));
                }
            }

            BalanceForecast.Until horizon = null;
            JsonNode value = body.get("horizon");
            if (value != null) {
                if (value.isTextual() && (value.asText().equals("NEXT_INCOME") || value.asText().equals("DAYS"))) {
                    horizon = BalanceForecast.Until.valueOf(value.asText());
                } else {
                    errors.add(new FieldErrorResponse("horizon", "NEXT_INCOME 또는 DAYS여야 합니다."));
                }
            }
            Long days = number(body, "defaultDays", 7, 30, false, "7, 14, 30 중 하나여야 합니다.", errors);
            if (days != null && !ForecastSettings.ALLOWED_DAYS.contains(days.intValue())) {
                errors.add(new FieldErrorResponse("defaultDays", "7, 14, 30 중 하나여야 합니다."));
                days = null;
            }
            Long threshold = number(body, "alertThreshold", 0, ForecastSettings.MAX_AMOUNT, false,
                    "안전 잔액은 0원 이상 2,147,483,647원 이하의 정수여야 합니다.", errors);
            Long income = number(body, "incomeCashflowItemId", 1, Long.MAX_VALUE, true,
                    "올바른 수입 항목을 골라 주세요.", errors);
            Long target = number(body, "dailyLivingCostTarget", 1, ForecastSettings.MAX_AMOUNT, true,
                    "하루 생활비 목표는 1원 이상 2,147,483,647원 이하의 정수여야 합니다.", errors);
            Boolean candidates = bool(body, "includeCandidates", errors);
            Boolean conservative = bool(body, "conservativeLine", errors);
            if (!errors.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.copyOf(errors)));
            }
            return new Patch(horizon, days == null ? null : days.intValue(), threshold, body.has("incomeCashflowItemId"),
                    income, body.has("dailyLivingCostTarget"), target, candidates, conservative);
        }

        /** 정수 칸. 보내지 않았거나(또는 null을 허용하는 칸의 null) 틀리면 null을 돌려준다. 소수·문자열·범위 밖은 오류다. */
        private static Long number(JsonNode body, String field, long min, long max, boolean nullable, String reason,
                List<FieldErrorResponse> errors) {
            JsonNode value = body.get(field);
            if (value == null || (nullable && value.isNull())) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToLong()
                    || value.longValue() < min || value.longValue() > max) {
                errors.add(new FieldErrorResponse(field, reason));
                return null;
            }
            return value.longValue();
        }

        private static Boolean bool(JsonNode body, String field, List<FieldErrorResponse> errors) {
            JsonNode value = body.get(field);
            if (value == null) {
                return null;
            }
            if (!value.isBoolean()) {
                errors.add(new FieldErrorResponse(field, "true 또는 false여야 합니다."));
                return null;
            }
            return value.booleanValue();
        }
    }
}
