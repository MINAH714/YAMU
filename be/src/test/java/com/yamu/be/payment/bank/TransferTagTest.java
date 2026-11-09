package com.yamu.be.payment.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferTagTest {

    @Test
    void 종류마다_정해진_글자와_번호로_요약을_만든다() {
        assertThat(TransferTag.payment(1025).value()).isEqualTo("YAMU-P1025");
        assertThat(TransferTag.bill(88).value()).isEqualTo("YAMU-B88");
        assertThat(TransferTag.refund(91).value()).isEqualTo("YAMU-R91");
        assertThat(TransferTag.checkRefund(1025).value()).isEqualTo("YAMU-C1025");
    }

    @Test
    void 결제와_청구는_출금이고_환급과_체크카드_환불은_입금이다() {
        assertThat(TransferTag.payment(1).direction()).isEqualTo(BankDirection.WITHDRAWAL);
        assertThat(TransferTag.bill(1).direction()).isEqualTo(BankDirection.WITHDRAWAL);
        assertThat(TransferTag.refund(1).direction()).isEqualTo(BankDirection.DEPOSIT);
        assertThat(TransferTag.checkRefund(1).direction()).isEqualTo(BankDirection.DEPOSIT);
    }

    @Test
    void 만든_요약을_다시_읽으면_같은_태그다() {
        for (TransferTag tag : List.of(TransferTag.payment(1), TransferTag.bill(Long.MAX_VALUE),
                TransferTag.refund(91), TransferTag.checkRefund(1025))) {
            assertThat(TransferTag.parse(tag.value())).contains(tag);
        }
    }

    @Test
    void 형식이_정확히_같지_않으면_태그로_읽지_않는다() {
        // 앞자리 0을 허용하면 YAMU-P1과 YAMU-P01이 같은 결제인데 글자가 달라 거래내역에서 놓친다
        List<String> notTags = Arrays.asList(null, "", "초기 입금", "급여", "YAMU-P", "YAMU-P0", "YAMU-P01",
                "YAMU-X1", "yamu-p1", " YAMU-P1", "YAMU-P1 ", "YAMU-P1-2", "YAMU-P-1", "YAMU-P１",
                "YAMU-P9223372036854775808", "YAMU-P12345678901234567890");
        for (String summary : notTags) {
            assertThat(TransferTag.parse(summary)).as(summary).isEmpty();
        }
    }

    @Test
    void 번호는_1_이상이어야_한다() {
        assertThatThrownBy(() -> TransferTag.payment(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransferTag.refund(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferTag(null, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
