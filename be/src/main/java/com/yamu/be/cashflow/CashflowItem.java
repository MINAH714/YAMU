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
}
