package com.yamu.be.accounthistory;
import com.yamu.be.global.entity.*;
import com.yamu.be.payment.bank.*;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;
@Entity @Table(name="account_history_sync_run") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class HistoryRun extends BaseTimeEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="run_id") Long id;
    @Version Long version;
    Long streamId; long generation; LocalDate rangeFrom; LocalDate rangeTo;
    @Column(length=40) String status;
    Long reportedCount; Long receivedCount; LocalDateTime queriedAt;
    HistoryRun(HistoryStream stream, LocalDate from, LocalDate to) {
        streamId=stream.id; generation=stream.generation; rangeFrom=from; rangeTo=to; status="RUNNING";
    }
}
