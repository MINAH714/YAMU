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
    /** CONFIRMED 예정 / EXCLUDED 이번 건만 제외(행은 보존). */
    public enum Status { CONFIRMED, EXCLUDED }

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    @Column(name = "is_overridden", nullable = false)
    private boolean overridden;

    @Column(length = 500)
    private String memo;

    @Version
    @Column(nullable = false)
    private Long version;

    private CashflowOccurrence(CashflowItem item, LocalDate occurrenceDate) {
        this.cashflowItemId = item.getId();
        this.userId = item.getUserId();
        this.occurrenceDate = occurrenceDate;
        this.scheduledDate = occurrenceDate;
        this.title = item.getTitle();
        this.direction = item.getDirection();
        this.amount = item.getAmount();
        this.accountId = item.getAccountId();
        this.userCardId = item.getUserCardId();
        this.merchantCategoryCode = item.getMerchantCategoryCode();
        this.status = Status.CONFIRMED;
        this.memo = item.getMemo();
    }

    static CashflowOccurrence firstOf(CashflowItem item) {
        return of(item, item.getScheduledDate());
    }

    /** 규칙의 occurrenceDate 회차를 규칙 값 그대로 만든다. 이번 회차만 고치거나 뺄 때 쓴다. */
    static CashflowOccurrence of(CashflowItem item, LocalDate occurrenceDate) {
        return new CashflowOccurrence(item, occurrenceDate);
    }

    /** 이번 회차만 바꾼다. 이후 규칙이 바뀌어도 목록은 이 값을 쓴다. */
    void override(LocalDate scheduledDate, String title, Long amount, Long accountId, Long userCardId,
            String merchantCategoryCode, String memo) {
        this.scheduledDate = scheduledDate;
        this.title = title;
        this.amount = amount;
        this.accountId = accountId;
        this.userCardId = userCardId;
        this.merchantCategoryCode = merchantCategoryCode;
        this.memo = memo;
        this.overridden = true;
    }

    /** 이번 회차만 뺀다. 행을 남겨 같은 회차가 규칙에서 다시 계산되지 않게 한다. */
    void exclude() {
        this.status = Status.EXCLUDED;
    }
}
