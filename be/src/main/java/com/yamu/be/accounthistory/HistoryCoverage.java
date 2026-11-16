package com.yamu.be.accounthistory;
import com.yamu.be.global.entity.*;
import com.yamu.be.payment.bank.*;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;
@Entity @Table(name="account_history_coverage") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class HistoryCoverage extends BaseTimeEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="coverage_id") Long id;
    @Version Long version;
    Long streamId; Long runId; LocalDate rangeFrom; LocalDate rangeTo; boolean valid; Long openingBalance;
    @Enumerated(EnumType.STRING) @Column(length=24) OpeningEvidence openingEvidence;
    Long openingTransactionId;
    Long openingCoverageId;
    enum OpeningEvidence { NONE, FIRST_TRANSACTION, PREVIOUS_COVERAGE, QA_SCENARIO }
    HistoryCoverage(HistoryRun run, Long opening) {
        streamId=run.streamId; runId=run.id; rangeFrom=run.rangeFrom; rangeTo=run.rangeTo; valid=true; openingBalance=opening;
        openingEvidence=OpeningEvidence.NONE;
    }
}
