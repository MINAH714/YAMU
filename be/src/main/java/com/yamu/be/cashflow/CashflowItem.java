package com.yamu.be.cashflow;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cashflow_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashflowItem extends BaseTimeEntity {
    public enum Direction { INCOME, EXPENSE }
    public enum RecurrenceType { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }
    /** API 명세 「가계부 항목 목록」의 status. CANDIDATE·IGNORED는 반복 지출 탐지 후보용이다. */
    public enum Status { CONFIRMED, CANDIDATE, IGNORED }
    public enum ItemKind { SCHEDULED, PURCHASE_PLAN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cashflow_item_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "linked_account_id")
    private Long accountId;

    @Column(name = "user_card_id")
    private Long userCardId;

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Column
    private Long amount;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type", nullable = false, length = 16)
    private RecurrenceType recurrenceType;

    @Column(name = "recurrence_end_date")
    private LocalDate recurrenceEndDate;

    @Column(name = "merchant_category_code", length = 64)
    private String merchantCategoryCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    @Column(length = 500)
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", nullable = false, length = 16)
    private ItemKind itemKind;

    /** 이후 일정 변경으로 나뉜 경우 바로 앞 규칙. 시리즈의 첫 규칙은 NULL이다. */
    @Column(name = "previous_cashflow_item_id")
    private Long previousCashflowItemId;

    @Version
    @Column(nullable = false)
    private Long version;

    CashflowItem(Long userId, Long accountId, Long userCardId, String title, Direction direction,
            Long amount, LocalDate scheduledDate, RecurrenceType recurrenceType,
            LocalDate recurrenceEndDate, String merchantCategoryCode, String memo) {
        this.userId = userId;
        this.accountId = accountId;
        this.userCardId = userCardId;
        this.title = title;
        this.direction = direction;
        this.amount = amount;
        this.scheduledDate = scheduledDate;
        this.recurrenceType = recurrenceType;
        this.recurrenceEndDate = recurrenceEndDate;
        this.merchantCategoryCode = merchantCategoryCode;
        this.status = Status.CONFIRMED;
        this.memo = memo;
        this.itemKind = ItemKind.SCHEDULED;
    }

    /** 아직 시작하지 않은 규칙을 제자리에서 바꾼다. 유형(direction)은 바꾸지 않는다. */
    void change(String title, Long amount, LocalDate scheduledDate, RecurrenceType recurrenceType,
            LocalDate recurrenceEndDate, Long accountId, Long userCardId, String merchantCategoryCode, String memo) {
        this.title = title;
        this.amount = amount;
        this.scheduledDate = scheduledDate;
        this.recurrenceType = recurrenceType;
        this.recurrenceEndDate = recurrenceEndDate;
        this.accountId = accountId;
        this.userCardId = userCardId;
        this.merchantCategoryCode = merchantCategoryCode;
        this.memo = memo;
    }

    /** cutoff 전날로 반복을 끝낸다. cutoff는 첫 발생일보다 늦어야 한다(종료일 ≥ 첫 발생일 제약). */
    void closeBefore(LocalDate cutoff) {
        this.recurrenceEndDate = cutoff.minusDays(1);
    }

    /** 탐지 후보를 확인(CONFIRMED)하거나 무시(IGNORED)한다. 바꿀 수 있는 전환인지는 서비스가 확인한다. */
    void changeStatus(Status status) {
        this.status = status;
    }

    /** 이 규칙을 이어받는 새 규칙. 상태는 이어받고, 이 규칙은 closeBefore로 따로 닫는다. */
    CashflowItem successor(String title, Long amount, LocalDate scheduledDate, RecurrenceType recurrenceType,
            LocalDate recurrenceEndDate, Long accountId, Long userCardId, String merchantCategoryCode, String memo) {
        CashflowItem next = new CashflowItem(userId, accountId, userCardId, title, direction, amount, scheduledDate,
                recurrenceType, recurrenceEndDate, merchantCategoryCode, memo);
        next.previousCashflowItemId = id;
        next.status = status;
        return next;
    }
}
