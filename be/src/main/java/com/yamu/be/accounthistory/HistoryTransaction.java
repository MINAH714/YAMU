package com.yamu.be.accounthistory;
import com.yamu.be.global.entity.*;
import com.yamu.be.payment.bank.*;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;
@Entity @Table(name="account_transaction") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class HistoryTransaction extends BaseCreatedTimeEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="transaction_id") Long id;

    Long streamId;
    @Column(length=128) String externalId;
    LocalDateTime occurredAt;
    @Enumerated(EnumType.STRING) @Column(length=12) BankDirection direction;
    long amount; long balanceAfter;
    @Column(columnDefinition="char(64)") String contentHash;
    HistoryTransaction(Long stream, BankTransaction row) {
        streamId=stream; externalId=row.bankTransactionNo();
        occurredAt=row.date().atTime(row.time()).atZone(ZoneId.of("Asia/Seoul")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        direction=row.direction(); amount=row.amount(); balanceAfter=row.balanceAfter(); contentHash=hash(row);
    }
    BankTransaction row() {
        var kst=occurredAt.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul"));
        return new BankTransaction(externalId,kst.toLocalDate(),kst.toLocalTime(),direction,amount,balanceAfter,"");
    }
    static String hash(BankTransaction row) {
        try {
            String value=row.date()+"|"+row.time()+"|"+row.direction()+"|"+row.amount()+"|"+row.balanceAfter();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
