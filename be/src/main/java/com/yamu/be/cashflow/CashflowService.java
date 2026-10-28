package com.yamu.be.cashflow;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashflowService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final CashflowItemRepository items;
    private final CashflowOccurrenceRepository occurrences;
    private final CashflowOwnershipLookup ownership;

    CashflowService(CashflowItemRepository items, CashflowOccurrenceRepository occurrences,
            CashflowOwnershipLookup ownership) {
        this.items = items;
        this.occurrences = occurrences;
        this.ownership = ownership;
    }

    /** 예정 수입·지출 규칙과 첫 발생 건을 저장하고 저장된 규칙을 돌려준다. */
    @Transactional
    public Item create(Long authenticatedUserId, CreateCommand command) {
        requireUser(authenticatedUserId);
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String title = command.title() == null ? null : command.title().trim();
        CashflowItem.RecurrenceType recurrence = validate(command, title);
        if (command.accountId() != null
                && !ownership.hasConnectedAccount(authenticatedUserId, command.accountId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (command.userCardId() != null
                && !ownership.hasRegisteredCard(authenticatedUserId, command.userCardId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        CashflowItem item = items.save(new CashflowItem(authenticatedUserId,
                command.accountId(), command.userCardId(), title,
                command.direction(), command.amount(), command.scheduledDate(), recurrence,
                command.recurrenceEndDate(), command.merchantCategoryCode(), command.memo()));
        occurrences.save(CashflowOccurrence.firstOf(item));
        return Item.from(item);
    }

    /**
     * 조회 기간과 겹치는 사용자의 예정 규칙을 돌려준다. 반복 회차를 날짜별로 펼치지 않는다.
     * from을 생략하면 한국 날짜 오늘, to를 생략하면 from을 포함한 30일째 날이다.
     */
    @Transactional(readOnly = true)
    public List<Item> listRules(Long authenticatedUserId, LocalDate from, LocalDate to,
            CashflowItem.Direction direction, CashflowItem.Status status) {
        requireUser(authenticatedUserId);
        LocalDate start = from == null ? LocalDate.now(KOREA) : from;
        LocalDate end = to == null ? start.plusDays(29) : to;
        if (start.isAfter(end)) {
            throw invalid("to", "조회 종료일은 시작일보다 빠를 수 없습니다.");
        }
        return items.findRulesForUser(authenticatedUserId, start, end, CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, direction,
                status == null ? CashflowItem.Status.CONFIRMED : status).stream().map(Item::from).toList();
    }

    private static CashflowItem.RecurrenceType validate(CreateCommand command, String title) {
        if (command.direction() == null) {
            throw invalid("type", "수입·지출 유형을 선택해 주세요.");
        }
        if (title == null || title.isEmpty()) {
            throw invalid("title", "이름을 입력해 주세요.");
        }
        if (title.length() > 30) {
            throw invalid("title", "이름은 30자 이하로 입력해 주세요.");
        }
        if (command.amount() == null || command.amount() <= 0) {
            throw invalid("amount", "금액은 1원 이상이어야 합니다.");
        }
        if (command.scheduledDate() == null) {
            throw invalid("date", "첫 발생일을 입력해 주세요.");
        }
        if (command.scheduledDate().isBefore(LocalDate.now(KOREA))) {
            throw invalid("date", "첫 발생일은 오늘 이후여야 합니다.");
        }

        CashflowItem.RecurrenceType recurrence = command.recurrenceType() == null
                ? CashflowItem.RecurrenceType.NONE : command.recurrenceType();
        if (recurrence != CashflowItem.RecurrenceType.NONE
                && recurrence != CashflowItem.RecurrenceType.WEEKLY
                && recurrence != CashflowItem.RecurrenceType.MONTHLY) {
            throw invalid("recurrence.frequency", "반복은 WEEKLY 또는 MONTHLY만 지원합니다.");
        }
        if (command.recurrenceEndDate() != null) {
            if (recurrence == CashflowItem.RecurrenceType.NONE) {
                throw invalid("recurrence.endDate", "반복하지 않는 항목에는 종료일을 지정할 수 없습니다.");
            }
            if (command.recurrenceEndDate().isBefore(command.scheduledDate())) {
                throw invalid("recurrence.endDate", "반복 종료일은 첫 발생일보다 빠를 수 없습니다.");
            }
        }
        if (command.dayOfMonth() != null && (recurrence != CashflowItem.RecurrenceType.MONTHLY
                || command.dayOfMonth() != command.scheduledDate().getDayOfMonth())) {
            throw invalid("recurrence.dayOfMonth", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다.");
        }
        if (command.dayOfWeek() != null && (recurrence != CashflowItem.RecurrenceType.WEEKLY
                || command.dayOfWeek() != command.scheduledDate().getDayOfWeek())) {
            throw invalid("recurrence.dayOfWeek", "매주 반복 요일은 첫 발생일의 요일과 같아야 합니다.");
        }

        if (command.merchantCategoryCode() != null && command.merchantCategoryCode().length() > 64) {
            throw invalid("merchantCategoryCode", "가맹점 업종 코드는 64자 이하여야 합니다.");
        }
        if (command.memo() != null && command.memo().length() > 100) {
            throw invalid("memo", "메모는 100자 이하로 입력해 주세요.");
        }
        if (command.accountId() != null && command.accountId() <= 0) {
            throw invalid("accountId", "올바른 계좌를 선택해 주세요.");
        }
        if (command.userCardId() != null && command.userCardId() <= 0) {
            throw invalid("userCardId", "올바른 카드를 선택해 주세요.");
        }
        // 잔고예보가 귀속 계좌가 없는 항목을 임의 배분하지 않으므로 출금·입금 경로를 하나로 정한다.
        if (command.direction() == CashflowItem.Direction.INCOME && command.userCardId() != null) {
            throw invalid("userCardId", "수입에는 카드를 지정할 수 없습니다.");
        }
        if (command.accountId() == null && command.userCardId() == null) {
            throw invalid("accountId", "입출금 계좌나 결제 카드 중 하나를 선택해 주세요.");
        }
        if (command.accountId() != null && command.userCardId() != null) {
            throw invalid("userCardId", "계좌와 카드 중 하나만 선택해 주세요.");
        }
        return recurrence;
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    private static void requireUser(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record CreateCommand(String title, CashflowItem.Direction direction, Long amount,
            LocalDate scheduledDate, CashflowItem.RecurrenceType recurrenceType,
            Integer dayOfMonth, DayOfWeek dayOfWeek, LocalDate recurrenceEndDate,
            Long accountId, Long userCardId, String merchantCategoryCode, String memo) {}

    public record Item(Long id, Long accountId, Long userCardId, String title,
            CashflowItem.Direction direction, Long amount, LocalDate scheduledDate,
            CashflowItem.RecurrenceType recurrenceType, LocalDate recurrenceEndDate,
            String merchantCategoryCode, CashflowItem.Status status, String memo) {
        static Item from(CashflowItem item) {
            return new Item(item.getId(), item.getAccountId(), item.getUserCardId(),
                    item.getTitle(), item.getDirection(), item.getAmount(),
                    item.getScheduledDate(), item.getRecurrenceType(), item.getRecurrenceEndDate(),
                    item.getMerchantCategoryCode(), item.getStatus(), item.getMemo());
        }
    }
}
