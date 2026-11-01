package com.yamu.be.payment.barcode;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.pos.PaymentRequestView;
import com.yamu.be.payment.pos.PaymentRequestViewReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

/** 결제 화면에 띄울 1회용 통합 바코드를 발급하고 상태를 알려준다. (FR-007, FR-010, DEC-003) */
@Service
public class BarcodeService {

    /** 바코드 유효 시간. 가정값(DEC-003 미결정). API 초안은 180초였으나 BE 2가 60초로 낮춰 시험 중이다. */
    static final Duration VALIDITY = Duration.ofSeconds(60);
    /** Redis 보관 시간. 만료된 뒤에도 잠시 남겨 두어 조회에 404 대신 EXPIRED로 답한다. */
    static final Duration RETENTION = Duration.ofMinutes(10);
    static final String QR_PREFIX = "yamu://pay/";
    private static final int MAX_TOKEN_ATTEMPTS = 3;

    private final BarcodeStore store;
    private final PaymentCardLookup cards;
    private final StepUpService stepUp;
    private final PaymentRequestViewReader paymentViews;
    private final BarcodeTokenGenerator tokens;
    private final Clock clock;

    /** clock은 {@code ClockConfig}의 빈이다. 테스트가 시계를 옮겨 만료를 확인할 수 있게 직접 만들지 않는다. */
    BarcodeService(BarcodeStore store, PaymentCardLookup cards, StepUpService stepUp,
            PaymentRequestViewReader paymentViews, BarcodeTokenGenerator tokens, Clock clock) {
        this.store = store;
        this.cards = cards;
        this.stepUp = stepUp;
        this.paymentViews = paymentViews;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 바코드를 발급한다. 결제에 쓸 ACTIVE 카드가 없으면 발급하지 않고, 있으면 본인 인증(BARCODE)을 확인한다.
     * 같은 사용자의 아직 쓰지 않은 이전 바코드는 만료된다.
     */
    public Issued issue(AuthUser me, String stepUpToken) {
        long activeCardCount = cards.countActiveCards(me.userId());
        if (activeCardCount == 0) {
            throw new BusinessException(ErrorCode.NO_ACTIVE_CARD);
        }
        // 본인 인증 토큰은 확인하는 순간 쓰여 없어진다. 카드 확인처럼 실패할 수 있는 검증을 먼저 끝내야
        // 그 검증에 걸렸을 때 사용자가 본인 인증을 다시 하지 않는다.
        stepUp.requireStepUp(me, StepUpPurpose.BARCODE, stepUpToken);
        Instant now = clock.instant();
        for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
            Barcode barcode = new Barcode(tokens.next(), me.userId(), BarcodeStatus.ISSUED, now,
                    now.plus(VALIDITY));
            if (store.issue(barcode, RETENTION)) {
                return Issued.of(barcode, activeCardCount);
            }
        }
        // 10^16가지(약 53비트) 난수가 세 번 연속 겹치는 일은 사실상 없다. 생기면 난수 생성에 문제가 있는 것이므로 서버 오류로 둔다.
        throw new IllegalStateException("바코드 토큰을 만들지 못했습니다.");
    }

    /**
     * 내 바코드의 지금 상태. 형식이 다르거나, 없거나, 다른 사용자의 바코드면 404 BARCODE_NOT_FOUND다.
     * POS가 스캔했으면 결제 요청(POS 조건·추천 상위 2장)을 같이 돌려준다.
     */
    public State get(Long userId, String barcodeToken) {
        if (!BarcodeTokenGenerator.isWellFormed(barcodeToken)) {
            throw new BusinessException(ErrorCode.BARCODE_NOT_FOUND);
        }
        Barcode barcode = store.find(barcodeToken)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND));
        PaymentRequestView payment = barcode.paymentId() == null ? null
                : paymentViews.find(barcode.paymentId(), userId).orElse(null);
        if (payment != null && payment.status() == PaymentStatus.EXPIRED && barcode.status() == BarcodeStatus.SCANNED) {
            // 결제가 조회하는 자리에서 막 만료됐으면 바코드도 EXPIRED로 바뀌어 있다(PaymentExpiry). 바뀐 값을 다시 읽는다
            barcode = store.find(barcodeToken).orElse(barcode);
        }
        return State.of(barcode, clock.instant(), payment);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 발급 응답. API 명세 「결제 바코드 발급」 Response. */
    public record Issued(
            String barcodeToken,
            String barcodeValue,
            String qrValue,
            BarcodeStatus status,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            long activeCardCount) {

        static Issued of(Barcode barcode, long activeCardCount) {
            return new Issued(barcode.token(), barcode.token(), QR_PREFIX + barcode.token(), barcode.status(),
                    utc(barcode.issuedAt()), utc(barcode.expiresAt()), activeCardCount);
        }
    }

    /**
     * 조회 응답. API 명세 「바코드 상태·결제 요청 조회」 Response.
     *
     * @param payment POS가 스캔해 결제 요청이 생긴 뒤 채운다. 스캔 전이면 null
     */
    public record State(BarcodeStatus status, LocalDateTime expiresAt, PaymentRequestView payment) {

        static State of(Barcode barcode, Instant now, PaymentRequestView payment) {
            return new State(barcode.statusAt(now), utc(barcode.expiresAt()), payment);
        }
    }
}
