package com.yamu.be.cashflow;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    @Transactional
    public Item create(Long authenticatedUserId, CreateCommand command) {
        requireUser(authenticatedUserId);
        if (command == null || command.direction() == null || command.title() == null
                || command.title().isBlank() || command.title().length() > 30
                || command.amount() == null || command.amount() <= 0
                || command.scheduledDate() == null
                || command.scheduledDate().isBefore(LocalDate.now(KOREA))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        CashflowItem.RecurrenceType recurrence = command.recurrenceType() == null
                ? CashflowItem.RecurrenceType.NONE : command.recurrenceType();
        if (recurrence != CashflowItem.RecurrenceType.NONE
                && recurrence != CashflowItem.RecurrenceType.WEEKLY
                && recurrence != CashflowItem.RecurrenceType.MONTHLY) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if ((recurrence == CashflowItem.RecurrenceType.NONE && command.recurrenceEndDate() != null)
                || (command.recurrenceEndDate() != null
                    && command.recurrenceEndDate().isBefore(command.scheduledDate()))
                || (command.merchantCategoryCode() != null && command.merchantCategoryCode().length() > 64)
                || (command.memo() != null && command.memo().length() > 100)
                || (command.direction() == CashflowItem.Direction.INCOME && command.userCardId() != null)
                || (command.accountId() != null && command.accountId() <= 0)
                || (command.userCardId() != null && command.userCardId() <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (command.accountId() != null
                && !ownership.hasConnectedAccount(authenticatedUserId, command.accountId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (command.userCardId() != null
                && !ownership.hasActiveCard(authenticatedUserId, command.userCardId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        CashflowItem item = items.save(new CashflowItem(authenticatedUserId,
                command.accountId(), command.userCardId(), command.title().trim(),
                command.direction(), command.amount(), command.scheduledDate(), recurrence,
                command.recurrenceEndDate(), command.merchantCategoryCode(), command.memo()));
        occurrences.save(CashflowOccurrence.firstOf(item));
        return Item.from(item);
    }

    @Transactional(readOnly = true)
    public List<Item> listRules(Long authenticatedUserId, LocalDate from, LocalDate to,
            CashflowItem.Direction direction) {
        requireUser(authenticatedUserId);
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return items.findRulesForUser(authenticatedUserId, from, to,
                CashflowItem.RecurrenceType.NONE, direction).stream().map(Item::from).toList();
    }

    private static void requireUser(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record CreateCommand(String title, CashflowItem.Direction direction, Long amount,
            LocalDate scheduledDate, CashflowItem.RecurrenceType recurrenceType,
            LocalDate recurrenceEndDate, Long accountId, Long userCardId,
            String merchantCategoryCode, String memo) {}

    public record Item(Long id, Long accountId, Long userCardId, String title,
            CashflowItem.Direction direction, Long amount, LocalDate scheduledDate,
            CashflowItem.RecurrenceType recurrenceType, LocalDate recurrenceEndDate,
            String merchantCategoryCode, String status, String memo) {
        static Item from(CashflowItem item) {
            return new Item(item.getId(), item.getAccountId(), item.getUserCardId(),
                    item.getTitle(), item.getDirection(), item.getAmount(),
                    item.getScheduledDate(), item.getRecurrenceType(), item.getRecurrenceEndDate(),
                    item.getMerchantCategoryCode(), item.getStatus(), item.getMemo());
        }
    }
}
