package com.yamu.be.payment.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BarcodeServiceTest {

    static final Instant NOW = Instant.parse("2026-10-28T05:30:00Z");
    static final AuthUser ME = new AuthUser(7L, 1L);

    @Mock PaymentCardLookup cards;
    @Mock StepUpService stepUp;

    InMemoryBarcodeStore store;
    MutableClock clock;
    BarcodeService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryBarcodeStore();
        clock = new MutableClock(NOW);
        service = new BarcodeService(store, cards, stepUp, new BarcodeTokenGenerator(), clock);
    }

    @Test
    void 발급하면_숫자_16자리_코드와_60초_유효시간과_활성_카드_수를_돌려준다() {
        when(cards.countActiveCards(7L)).thenReturn(3L);

        BarcodeService.Issued issued = service.issue(ME, "step-up");

        assertThat(issued.barcodeToken()).matches("[0-9]{16}");
        assertThat(issued.barcodeValue()).isEqualTo(issued.barcodeToken());
        assertThat(issued.qrValue()).isEqualTo("yamu://pay/" + issued.barcodeToken());
        assertThat(issued.status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(issued.issuedAt()).isEqualTo(LocalDateTime.parse("2026-10-28T05:30:00"));
        assertThat(issued.expiresAt()).isEqualTo(LocalDateTime.parse("2026-10-28T05:31:00"));
        assertThat(issued.activeCardCount()).isEqualTo(3L);
        verify(stepUp).requireStepUp(ME, StepUpPurpose.BARCODE, "step-up");
    }

    @Test
    void 결제할_ACTIVE_카드가_없으면_본인_인증_토큰을_쓰지_않고_발급하지_않는다() {
        when(cards.countActiveCards(7L)).thenReturn(0L);

        assertThatThrownBy(() -> service.issue(ME, "step-up"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_ACTIVE_CARD);
        // 토큰을 쓰지 않았으므로 카드를 등록한 뒤 같은 토큰으로 다시 발급할 수 있다.
        verify(stepUp, never()).requireStepUp(any(), any(), any());
        assertThat(store.size()).isZero();
    }

    @Test
    void 본인_인증에_실패하면_발급하지_않는다() {
        when(cards.countActiveCards(7L)).thenReturn(1L);
        when(stepUp.requireStepUp(ME, StepUpPurpose.BARCODE, null))
                .thenThrow(new BusinessException(ErrorCode.STEP_UP_REQUIRED));

        assertThatThrownBy(() -> service.issue(ME, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STEP_UP_REQUIRED);
        assertThat(store.size()).isZero();
    }

    @Test
    void 다시_발급하면_이전_바코드는_지우지_않고_EXPIRED로_남기며_다른_사용자는_건드리지_않는다() {
        AuthUser other = new AuthUser(8L, 2L);
        when(cards.countActiveCards(7L)).thenReturn(1L);
        when(cards.countActiveCards(8L)).thenReturn(1L);
        String otherToken = service.issue(other, "other-step-up").barcodeToken();

        String first = service.issue(ME, "step-up-1").barcodeToken();
        String second = service.issue(ME, "step-up-2").barcodeToken();

        // 이전 코드는 404가 아니라 EXPIRED로 조회된다(보관 시간 안에서는 남아 있다)
        assertThat(service.get(7L, first).status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(service.get(7L, second).status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(service.get(8L, otherToken).status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(store.size()).isEqualTo(3);
        // 토큰은 발급마다 정확히 한 번씩 소비된다
        verify(stepUp).requireStepUp(ME, StepUpPurpose.BARCODE, "step-up-1");
        verify(stepUp).requireStepUp(ME, StepUpPurpose.BARCODE, "step-up-2");
        verify(stepUp).requireStepUp(other, StepUpPurpose.BARCODE, "other-step-up");
        verifyNoMoreInteractions(stepUp);
    }

    @Test
    void 발급_시각_기준_60초가_만료_시각이고_만료_정각부터_EXPIRED다() {
        when(cards.countActiveCards(7L)).thenReturn(1L);

        BarcodeService.Issued issued = service.issue(ME, "step-up");

        assertThat(Duration.between(issued.issuedAt(), issued.expiresAt())).isEqualTo(Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(60));
        assertThat(service.get(7L, issued.barcodeToken()).status()).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 보관_시간이_끝나_저장소에서_사라진_코드는_404다() {
        when(cards.countActiveCards(7L)).thenReturn(1L);
        String token = service.issue(ME, "step-up").barcodeToken();
        clock.advance(Duration.ofMinutes(10));
        store.remove(token); // Redis라면 보관 시간(10분)이 지나 키가 지워진 상태

        assertThatThrownBy(() -> service.get(7L, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BARCODE_NOT_FOUND);
    }

    @Test
    void 발급_후_59초까지는_ISSUED이고_60초부터_EXPIRED다() {
        when(cards.countActiveCards(7L)).thenReturn(1L);
        String token = service.issue(ME, "step-up").barcodeToken();

        clock.advance(Duration.ofSeconds(59));
        assertThat(service.get(7L, token).status()).isEqualTo(BarcodeStatus.ISSUED);

        clock.advance(Duration.ofSeconds(1));
        BarcodeService.State expired = service.get(7L, token);
        assertThat(expired.status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(expired.expiresAt()).isEqualTo(LocalDateTime.parse("2026-10-28T05:31:00"));
        assertThat(expired.payment()).isNull();
    }

    @Test
    void 다른_사용자의_바코드는_찾을_수_없다() {
        when(cards.countActiveCards(7L)).thenReturn(1L);
        String token = service.issue(ME, "step-up").barcodeToken();

        assertThatThrownBy(() -> service.get(8L, token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BARCODE_NOT_FOUND);
    }

    @Test
    void 형식이_다른_토큰은_저장소를_조회하지_않고_찾을_수_없다() {
        // 15·17자리, 띄어쓴 번호, 하이픈, 전각 숫자, 예전 YAMU 형식, 사용자 키 모양, 빈 값
        for (String token : List.of("123456789012345", "12345678901234567", "1234 5678 9012 3456",
                "1234-5678-9012-3456", "１２３４５６７８９０１２３４５６", "YAMU8F2A9C1D7E0B4C11", "user:7", "")) {
            assertThatThrownBy(() -> service.get(7L, token))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.BARCODE_NOT_FOUND);
        }
        assertThat(store.findCount()).isZero();
    }

    @Test
    void 형식은_맞지만_없는_토큰은_저장소를_확인한_뒤_찾을_수_없다() {
        assertThatThrownBy(() -> service.get(7L, "0000000000000000"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BARCODE_NOT_FOUND);
        assertThat(store.findCount()).isEqualTo(1);
    }

    @Test
    void 토큰이_이미_있으면_새_토큰으로_다시_만든다() {
        String taken = "0000000000000001";
        String fresh = "0000000000000002";
        store.issue(new Barcode(taken, 9L, BarcodeStatus.ISSUED, NOW, NOW.plusSeconds(60)), Duration.ofMinutes(10));
        Deque<String> generated = new ArrayDeque<>(List.of(taken, fresh));
        BarcodeTokenGenerator tokens = new BarcodeTokenGenerator() {
            @Override
            String next() {
                return generated.removeFirst();
            }
        };
        service = new BarcodeService(store, cards, stepUp, tokens, clock);
        when(cards.countActiveCards(7L)).thenReturn(1L);

        assertThat(service.issue(ME, "step-up").barcodeToken()).isEqualTo(fresh);
        assertThat(store.find(taken)).get().extracting(Barcode::userId).isEqualTo(9L);
    }

    /** {@link RedisBarcodeStore}와 같은 규칙을 메모리에서 흉내 낸다. Redis 동작 자체는 RedisBarcodeStoreTest가 확인한다. */
    static class InMemoryBarcodeStore implements BarcodeStore {

        private final Map<String, Barcode> barcodes = new HashMap<>();
        private final Map<Long, String> currentTokens = new HashMap<>();
        private int findCount;

        @Override
        public synchronized boolean issue(Barcode barcode, Duration retention) {
            if (barcodes.containsKey(barcode.token())) {
                return false;
            }
            String previous = currentTokens.get(barcode.userId());
            Barcode old = previous == null ? null : barcodes.get(previous);
            if (old != null && old.status() == BarcodeStatus.ISSUED) {
                barcodes.put(previous, new Barcode(old.token(), old.userId(), BarcodeStatus.EXPIRED,
                        old.issuedAt(), old.expiresAt()));
            }
            barcodes.put(barcode.token(), barcode);
            currentTokens.put(barcode.userId(), barcode.token());
            return true;
        }

        @Override
        public synchronized Optional<Barcode> find(String token) {
            findCount++;
            return Optional.ofNullable(barcodes.get(token));
        }

        int size() {
            return barcodes.size();
        }

        /** find가 불린 횟수. Redis라면 조회 명령이 나간 횟수다. */
        synchronized int findCount() {
            return findCount;
        }

        synchronized void remove(String token) {
            barcodes.remove(token);
        }
    }

    static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
