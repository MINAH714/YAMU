package com.yamu.be.payment.barcode;

import com.yamu.be.auth.AuthProperties;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.barcode.Barcode.IssueAuth;
import com.yamu.be.payment.barcode.BarcodeStore.Reissue;
import com.yamu.be.payment.barcode.BarcodeStore.ReissueResult;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.request.PaymentRequestView;
import com.yamu.be.payment.request.PaymentRequestViewReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 결제 화면에 띄울 1회용 통합 바코드를 발급하고 상태를 알려준다. (FR-007, FR-010, DEC-003) */
@Slf4j
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
    private final PaymentExpiry expiry;
    private final BarcodeTokenGenerator tokens;
    private final Clock clock;
    /** 처음 인증 뒤 다시 인증하지 않고 새 코드를 받을 수 있는 시간({@code yamu.auth.barcode-reissue-window}, 임시 10분) */
    private final Duration reissueWindow;

    /** clock은 {@code ClockConfig}의 빈이다. 테스트가 시계를 옮겨 만료를 확인할 수 있게 직접 만들지 않는다. */
    BarcodeService(BarcodeStore store, PaymentCardLookup cards, StepUpService stepUp,
            PaymentRequestViewReader paymentViews, PaymentExpiry expiry, BarcodeTokenGenerator tokens, Clock clock,
            AuthProperties authProperties) {
        this.store = store;
        this.cards = cards;
        this.stepUp = stepUp;
        this.paymentViews = paymentViews;
        this.expiry = expiry;
        this.tokens = tokens;
        this.clock = clock;
        this.reissueWindow = authProperties.barcodeReissueWindow();
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
        StepUpGrant grant = stepUp.requireStepUp(me, StepUpPurpose.BARCODE, stepUpToken);
        Instant now = clock.instant();
        // 재발급(S15P31E103-530)이 다시 인증하지 않아도 되는지 판단할 값. 새 코드는 이 값을 그대로 물려받는다
        IssueAuth auth = new IssueAuth(me.userDeviceId(), now, grant.method(), grant.auditLogId());
        for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
            Barcode barcode = new Barcode(tokens.next(), me.userId(), BarcodeStatus.ISSUED, now,
                    now.plus(VALIDITY), null, auth, null);
            if (store.issue(barcode, RETENTION)) {
                return Issued.of(barcode, activeCardCount);
            }
        }
        // 10^16가지(약 53비트) 난수가 세 번 연속 겹치는 일은 사실상 없다. 생기면 난수 생성에 문제가 있는 것이므로 서버 오류로 둔다.
        throw new IllegalStateException("바코드 토큰을 만들지 못했습니다.");
    }

    /**
     * 결제 화면의 [새 코드]. 본인 인증을 다시 받지 않고 이전 코드를 닫고 새 코드를 낸다(S15P31E103-530, 임시 정책).
     * <ul>
     *   <li>형식이 다르거나, 없거나(보관 종료 포함), 다른 사용자의 코드면 조회와 같이 404 BARCODE_NOT_FOUND</li>
     *   <li>결제에 쓸 ACTIVE 카드가 없으면 발급과 같이 NO_ACTIVE_CARD</li>
     *   <li>다른 기기에서 불렀거나, 처음 인증한 지 {@code reissueWindow}가 지났거나, 인증 정보가 없는 코드(이 기능 전 발급)면 403 STEP_UP_REQUIRED</li>
     *   <li>기기가 잠겼으면 DEVICE_LOCKED, 생체로 연 뒤 "간편 비밀번호만"으로 바꿨으면 STEP_UP_REQUIRED</li>
     *   <li>현재 코드가 아니거나 스캔 · 결제 · 취소에 쓰인 코드면 STEP_UP_REQUIRED(저장소가 한 번에 확인)</li>
     * </ul>
     * 시간이 지나 만료된 코드는 받는다. 같은 코드로 다시 오면(연타 · 응답이 끊긴 재시도) 앞서 낸 새 코드가 아직 쓸 수 있을 때 그 코드를 돌려준다.
     * 새 코드는 처음 인증 시각을 물려받으므로 재발급을 이어 붙여도 창이 늘지 않는다. 새 인증이 아니라 step_up_audit_log에 남기지 않고,
     * 코드에 물려 둔 처음 인증 기록 번호(auditLogId)로 따라간다.
     */
    public Issued reissue(AuthUser me, String previousToken) {
        if (!BarcodeTokenGenerator.isWellFormed(previousToken)) {
            throw new BusinessException(ErrorCode.BARCODE_NOT_FOUND);
        }
        Barcode previous = store.find(previousToken)
                .filter(found -> found.userId().equals(me.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND));
        long activeCardCount = cards.countActiveCards(me.userId());
        if (activeCardCount == 0) {
            throw new BusinessException(ErrorCode.NO_ACTIVE_CARD);
        }
        Instant now = clock.instant();
        IssueAuth auth = previous.auth();
        if (auth == null || !auth.userDeviceId().equals(me.userDeviceId())
                || !now.isBefore(auth.authAt().plus(reissueWindow))) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
        }
        stepUp.requireReusable(me, StepUpPurpose.BARCODE, auth.method());
        for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
            Barcode next = new Barcode(tokens.next(), me.userId(), BarcodeStatus.ISSUED, now, now.plus(VALIDITY),
                    null, auth, null);
            Reissue reissue = store.reissue(previousToken, next, RETENTION, now);
            if (reissue.result() == ReissueResult.REISSUED) {
                log.info("결제 코드 재발급 userId={} deviceId={} authAuditLogId={}", me.userId(), me.userDeviceId(),
                        auth.auditLogId());
                return Issued.of(next, activeCardCount);
            }
            if (reissue.result() == ReissueResult.ALREADY_REISSUED) {
                // 앞서 낸 새 코드를 그대로 돌려준다. 그사이 보관이 끝났거나 바뀌었으면 다시 인증
                return store.find(reissue.token())
                        .filter(found -> found.statusAt(now) == BarcodeStatus.ISSUED)
                        .map(found -> Issued.of(found, activeCardCount))
                        .orElseThrow(() -> new BusinessException(ErrorCode.STEP_UP_REQUIRED));
            }
            if (reissue.result() == ReissueResult.DENIED) {
                throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
            }
        }
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
        if (payment != null && isDue(payment) && expire(payment.paymentId())) {
            // 결제가 조회하는 자리에서 막 만료됐다. 바뀐 결제·바코드를 다시 읽는다
            payment = paymentViews.find(barcode.paymentId(), userId).orElse(payment);
            barcode = store.find(barcodeToken).orElse(barcode);
        }
        return State.of(barcode, clock.instant(), payment);
    }

    /** 아직 승인할 수 있는 결제(REQUESTED·DECLINED)인데 확정 마감이 지났는지. 판정 기준은 {@link PaymentExpiry}와 같다. */
    private boolean isDue(PaymentRequestView payment) {
        boolean confirmable = payment.status() == PaymentStatus.REQUESTED || payment.status() == PaymentStatus.DECLINED;
        return confirmable && !clock.instant().isBefore(payment.confirmDeadline().toInstant(ZoneOffset.UTC));
    }

    /** 마감이 지난 결제를 EXPIRED로 저장하고 바코드도 EXPIRED로 둔다(임시: 명세 Q4 제안). 바코드 반영 실패는 로그만 남긴다. */
    private boolean expire(Long paymentId) {
        if (expiry.expire(paymentId) != PaymentStatus.EXPIRED) {
            return false;
        }
        try {
            store.expireByPayment(paymentId);
        } catch (RuntimeException e) {
            log.warn("결제 만료 뒤 바코드 만료 반영 실패 paymentId={}", paymentId, e);
        }
        return true;
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
