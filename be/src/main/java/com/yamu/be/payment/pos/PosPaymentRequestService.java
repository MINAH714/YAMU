package com.yamu.be.payment.pos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.barcode.BarcodeStore.ScanResult;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.merchant.MerchantLookup;
import com.yamu.be.payment.merchant.MerchantLookup.Merchant;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POS 결제 요청 생성(API 명세 「POS 결제 요청 생성」, FR-008~FR-011, DEC-003, POL-013).
 *
 * <pre>
 * ① POS 키·posCode 확인 → 요청 merchantId와 설정의 브랜드 대조
 * ② 입력 검증(금액 → 할부 순), Idempotency-Key(UUID)
 * ③ 멱등: 같은 키 재전송은 처음 응답, 다른 본문은 409, 처리 중이면 409
 * ④ 바코드 미리 확인(없음 404 · 만료 410 · 사용 409)
 * ⑤ 트랜잭션: 결제 REQUESTED 저장(번호 확보) → 추천 계산·스냅샷 → 바코드 ISSUED→SCANNED(원자, 실패하면 롤백) → 커밋
 * ⑥ 멱등 결과 기억 → 201
 * </pre>
 *
 * <p>깨질 수 있는 곳: 같은 바코드를 두 POS가 동시에 보내면 둘 다 ⑤에 들어오지만 바코드 전환은 한 건만 성공하고 나머지는 롤백된다.
 * 같은 키 재전송은 ③의 Redis 결과와 DB request_key UNIQUE로 막는다. 커밋이 실패하면 바코드 전환을 되돌린다(보상).
 * 추천 계산 실패(예외)는 전체 롤백이고, 후보 없음(NO_ELIGIBLE_CARD)은 정상 결과다.
 */
@Slf4j
@Service
public class PosPaymentRequestService {

    private static final String REQUEST_KEY_CONSTRAINT = "uk_payment_request";

    private final PosTerminalResolver terminals;
    private final MerchantLookup merchants;
    private final BarcodeStore barcodes;
    private final PaymentTransactionRepository payments;
    private final CardRecommender recommender;
    private final RecommendationSnapshotStore snapshots;
    private final PosIdempotencyStore idempotency;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final PosProperties properties;
    private final Clock clock;

    public PosPaymentRequestService(PosTerminalResolver terminals, MerchantLookup merchants, BarcodeStore barcodes,
            PaymentTransactionRepository payments, CardRecommender recommender,
            RecommendationSnapshotStore snapshots, PosIdempotencyStore idempotency, RedisLocks locks,
            TransactionTemplate transaction, ObjectMapper objectMapper, PosProperties properties, Clock clock) {
        this.terminals = terminals;
        this.merchants = merchants;
        this.barcodes = barcodes;
        this.payments = payments;
        this.recommender = recommender;
        this.snapshots = snapshots;
        this.idempotency = idempotency;
        this.locks = locks;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public PosPaymentRequestResponse create(String posKey, PosPaymentRequest request, String idempotencyKey) {
        PosProperties.Terminal terminal = terminals.resolve(posKey, request.getPosCode());
        String key = requireIdempotencyKey(idempotencyKey);
        PaymentTerms terms = request.toTerms();
        Merchant merchant = merchants.findByBrandCode(terminal.brandCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND));
        if (!merchant.merchantId().equals(request.getMerchantId())) {
            throw new BusinessException(ErrorCode.MERCHANT_NOT_FOUND, "이 POS에서 결제할 수 없는 가맹점입니다.");
        }
        String fingerprint = fingerprint(terminal, request, terms);

        Optional<PosIdempotencyStore.Previous> previous = idempotency.find(terminal.posCode(), key);
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }
        Optional<RedisLocks.Lock> lock = locks.tryLock(idempotency.lockKey(terminal.posCode(), key),
                properties.idempotencyLockTtl());
        if (lock.isEmpty()) {
            return idempotency.find(terminal.posCode(), key)
                    .map(done -> replay(done, fingerprint))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            Optional<PosIdempotencyStore.Previous> done = idempotency.find(terminal.posCode(), key);
            if (done.isPresent()) {
                return replay(done.get(), fingerprint);
            }
            Instant now = clock.instant();
            Barcode barcode = requireIssued(request.getBarcodeToken(), now);
            PaymentTransaction payment = createPayment(terminal, merchant, barcode, request, terms, key,
                    fingerprint, now);
            idempotency.remember(terminal.posCode(), key,
                    new PosIdempotencyStore.Previous(fingerprint, payment.getId()));
            return response(payment, request.getOrderNo());
        }
    }

    /** 결제 저장·추천·바코드 전환을 한 트랜잭션으로. 바코드 전환이 실패하면 저장한 결제도 사라진다. */
    private PaymentTransaction createPayment(PosProperties.Terminal terminal, Merchant merchant, Barcode barcode,
            PosPaymentRequest request, PaymentTerms terms, String key, String fingerprint, Instant now) {
        LocalDateTime requestedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        MerchantContextSnapshot context = new MerchantContextSnapshot(MerchantContextSnapshot.CURRENT_VERSION,
                terminal.posCode(), merchant.brandCode(), merchant.name(), merchant.categoryCode(),
                terminal.channel(), terminal.facilityType(), request.getOrderNo(), terms.amount(), terms.plan(),
                terms.isInstallment() ? terms.installmentMonths() : null);
        // 바코드 전환까지 끝낸 뒤 커밋이 실패하면 되돌리기 위해 기억한다.
        Long[] scanned = new Long[1];
        try {
            return transaction.execute(status -> {
                PaymentTransaction saved = payments.saveAndFlush(PaymentTransaction.request(barcode.userId(),
                        merchant.merchantId(), terminal.channel(), terminal.facilityType(), write(context), key,
                        HexFormat.of().parseHex(fingerprint), terms.amount(), terms.installmentMonthsOrOne(),
                        requestedAt));
                RecommendationResult result = recommender.recommend(new RecommendationRequest(saved.getId(),
                        barcode.userId(), terms, new MerchantContext(merchant.merchantId(), merchant.categoryCode(),
                                terminal.channel().name(),
                                terminal.facilityType() == null ? null : terminal.facilityType().name()),
                        requestedAt));
                snapshots.save(saved.getId(), result, properties.snapshotTtl());
                ScanResult scan = barcodes.scan(barcode.token(), saved.getId(), properties.scannedRetention());
                if (scan != ScanResult.SCANNED) {
                    snapshots.delete(saved.getId());
                    throw scanFailure(scan);
                }
                scanned[0] = saved.getId();
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            compensate(barcode.token(), scanned[0]);
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains(REQUEST_KEY_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            compensate(barcode.token(), scanned[0]);
            throw e;
        }
    }

    /** 커밋 실패 등으로 결제 행 없이 바코드만 SCANNED로 남는 것을 막는다. 되돌리지 못해도 요청 실패는 그대로 알린다. */
    private void compensate(String token, Long paymentId) {
        if (paymentId == null) {
            return;
        }
        try {
            barcodes.revertScan(token, paymentId);
            snapshots.delete(paymentId);
        } catch (RuntimeException e) {
            log.warn("바코드 전환 되돌리기 실패 paymentId={}", paymentId, e);
        }
    }

    private Barcode requireIssued(String token, Instant now) {
        Barcode barcode = barcodes.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND));
        BarcodeStatus status = barcode.statusAt(now);
        if (status == BarcodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BARCODE_EXPIRED);
        }
        if (status != BarcodeStatus.ISSUED) {
            throw new BusinessException(ErrorCode.BARCODE_ALREADY_USED);
        }
        return barcode;
    }

    private static BusinessException scanFailure(ScanResult scan) {
        return switch (scan) {
            case EXPIRED -> new BusinessException(ErrorCode.BARCODE_EXPIRED);
            case ALREADY_USED -> new BusinessException(ErrorCode.BARCODE_ALREADY_USED);
            default -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND);
        };
    }

    /** 처음 결과를 다시 보여 준다. 같은 키에 다른 본문이면 409. */
    private PosPaymentRequestResponse replay(PosIdempotencyStore.Previous previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        PaymentTransaction payment = payments.findById(previous.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        String orderNo = readContext(payment).map(MerchantContextSnapshot::orderNo).orElse(null);
        return response(payment, orderNo);
    }

    private PosPaymentRequestResponse response(PaymentTransaction payment, String orderNo) {
        return new PosPaymentRequestResponse(payment.getId(), orderNo, payment.getStatus(),
                payment.getRequestedAt().plus(properties.confirmWindow()));
    }

    /** 명세 1장: 결제 요청에 Idempotency-Key(UUID v4)가 필수다. 소문자로 맞춰 request_key(ascii_bin)에 저장한다. */
    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String key = idempotencyKey.trim().toLowerCase();
        try {
            if (key.length() != 36 || !UUID.fromString(key).toString().equals(key)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key는 UUID 형식이어야 합니다.");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key는 UUID 형식이어야 합니다.");
        }
        return key;
    }

    /** 최초 요청의 불변 내용. 같은 키로 다른 내용이 오면 409 IDEMPOTENCY_KEY_REUSED. */
    static String fingerprint(PosProperties.Terminal terminal, PosPaymentRequest request, PaymentTerms terms) {
        String canonical = String.join("\n",
                terminal.posCode(),
                String.valueOf(request.getMerchantId()),
                request.getBarcodeToken(),
                Long.toString(terms.amount()),
                terms.plan().name(),
                terms.isInstallment() ? Integer.toString(terms.installmentMonths()) : "",
                request.getOrderNo() == null ? "" : request.getOrderNo());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(MerchantContextSnapshot context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<MerchantContextSnapshot> readContext(PaymentTransaction payment) {
        if (payment.getMerchantContextSnapshot() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payment.getMerchantContextSnapshot(), MerchantContextSnapshot.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
