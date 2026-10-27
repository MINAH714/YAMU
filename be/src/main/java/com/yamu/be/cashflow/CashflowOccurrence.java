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
@Table(name = "cashflow_occurrence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashflowOccurrence extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cashflow_occurrence_id")
    private Long id;

    @Column(name = "cashflow_item_id", nullable = false)
    private Long cashflowItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CashflowItem.Direction direction;

    @Column
    private Long amount;

    @Column(name = "linked_account_id")
    private Long accountId;

    @Column(name = "user_card_id")
    private Long userCardId;

    @Column(name = "merchant_category_code", length = 64)
    private String merchantCategoryCode;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "is_overridden", nullable = false)
    private boolean overridden;

    @Column(length = 500)
    private String memo;

    @Version
    @Column(nullable = false)
    private Long version;

    private CashflowOccurrence(CashflowItem item) {
        this.cashflowItemId = item.getId();
        this.userId = item.getUserId();
        this.occurrenceDate = item.getScheduledDate();
        this.scheduledDate = item.getScheduledDate();
        this.title = item.getTitle();
        this.direction = item.getDirection();
        this.amount = item.getAmount();
        this.accountId = item.getAccountId();
        this.userCardId = item.getUserCardId();
        this.merchantCategoryCode = item.getMerchantCategoryCode();
        this.status = item.getStatus();
        this.memo = item.getMemo();
    }

    static CashflowOccurrence firstOf(CashflowItem item) {
        return new CashflowOccurrence(item);
    }
}
