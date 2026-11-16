package com.yamu.be.payment.pos;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PosBasketTest {
    static PosBasket.Line line(String code, String quantity) {
        return new PosBasket.Line(code, new BigDecimal(quantity));
    }
    @Test void mixedBasketIncludesOnlyEligibleSubtotalAndSurvivesStorage() throws Exception {
        var lines = List.of(line("gs-jeyuk-dosirak","1"),line("gs-chips","2"));
        var basket = PosBasket.validate("GS25",lines,8900);
        assertThat(basket.ibkEligibleAmount()).isEqualTo(5500);
        var json = new ObjectMapper();
        assertThat(json.readValue(json.writeValueAsString(basket),PosBasket.Snapshot.class)).isEqualTo(basket);
        assertThat(PosBasket.fingerprint(lines)).isEqualTo(PosBasket.fingerprint(List.of(lines.get(1),lines.get(0))));
        assertThat(PosBasket.fingerprint(List.of(line("gs-chips","1"))))
                .isNotEqualTo(PosBasket.fingerprint(List.of(line("gs-banana-milk","1"))));
    }
    @Test void excludedUnknownAndAbsentAreDifferent() {
        assertThat(PosBasket.validate("GS25",List.of(line("gs-chips","1")),1700).ibkEligibleAmount()).isZero();
        assertThat(PosBasket.validate("GS25",List.of(line("gs-banana-milk","1")),1700).ibkEligibleAmount()).isNull();
        assertThat(PosBasket.validate("GS25",null,1700)).isNull();
    }
    @Test void rejectsTamperedTotalsUnknownProductsAndInvalidQuantities() {
        for (String quantity : List.of("0","-1","1.5","100","9999999999999999999")) {
            assertThatThrownBy(() -> PosBasket.validate("GS25",List.of(line("gs-cola",quantity)),2100)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> PosBasket.validate("GS25",List.of(line("gs-cola","1")),1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PosBasket.validate("STARBUCKS",List.of(line("gs-cola","1")),2100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PosBasket.validate("GS25",List.of(line("fake","1")),2100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PosBasket.validate("GS25",List.of(line("gs-cola","1"),line("gs-cola","1")),4200)).isInstanceOf(BusinessException.class);
    }
}
