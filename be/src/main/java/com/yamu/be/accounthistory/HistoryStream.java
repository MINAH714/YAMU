package com.yamu.be.accounthistory;
import com.yamu.be.global.entity.*;
import com.yamu.be.payment.bank.*;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;
@Entity @Table(name="account_history_stream") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class HistoryStream extends BaseTimeEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="stream_id") Long id;
    @Version Long version;
    Long linkedAccountId; Long userId;
    @Enumerated(EnumType.STRING) @Column(length=20) HistorySource source;
    @Column(length=64) String datasetKey;
    long generation; boolean active; LocalDate rangeFrom; LocalDate rangeTo;
    @Column(length=64) String inputHash;
    HistoryStream(Long account, Long user) { linkedAccountId=account; userId=user; source=HistorySource.SSAFY; active=true; }
}
