package com.yamu.be.payment.pos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.payment.IdempotencyKeys;
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
import com.yamu.be.payment.recommend.DatabaseRecommendationResultStore;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POS 결제 요청 생성(API 명세 「POS 결제 요청 생성」, FR-008~FR-011, DEC-003, POL-013).
 *
 * <pre>
 * ① POS 키·posCode 확인 → 요청 merchantId와 설정의 브랜드 대조 (키는 {@link PosKeyInterceptor}가 먼저 본다)
 * ② 입력 검증(금액 → 할부 순), Idempotency-Key(UUID)
 * ③ 멱등: 같은 키 재전송은 처음 결과, 다른 본문은 409, 처리 중이면 409.
 *    Redis에 기록이 없으면 DB의 request_key로 찾아 fingerprint를 대조한다(기록 실패·재시작 대비)
 * ④ 바코드 미리 확인(없음 404 · 만료 410 · 사용 409) → DB 트랜잭션 밖에서 카드 상태·잔액 준비
 * ⑤ 트랜잭션: 결제 REQUESTED 저장(번호 확보) → 바코드 ISSUED→SCANNED(원자, 실패하면 바로 롤백) → 추천 계산
 *    → 추천 결과 DB 보관(결제 행) → Redis 스냅샷 → 커밋
 * ⑥ 멱등 결과 기억(실패해도 ③의 DB 대조가 있으므로 201) → 201
 * </pre>
 *
 * <p>깨질 수 있는 곳: 같은 바코드를 두 POS가 동시에 보내면 둘 다 ⑤에 들어오지만 바코드 전환은 한 건만 성공하고 나머지는 추천 계산 전에 롤백된다.
 * ⑤가 예외로 끝나면 결제 행이 실제로 커밋됐는지 DB에서 다시 확인하고, 행이 없을 때만 바코드 전환을 되돌린다(보상). 행이 있으면 커밋된 것이므로
 * 처음 결과로 응답한다. 추천 계산 실패(예외)는 전체 롤백이고, 후보 없음(NO_ELIGIBLE_CARD)은 정상 결과다.
 *
 * <p>외부 잔액 조회는 저장 트랜잭션 전에 준비하고, 기존 결제 저장과 바코드 전환의 원자성은 유지한다.
 * 잔액은 추천 시점의 참고값이며 승인은 다시 확인한다. 되돌리는 사이 사용자가 새 바코드를 발급했으면 옛 바코드가 ISSUED로 돌아와 ISSUED가 둘이 될 수 있다.
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
    private final DatabaseRecommendationResultStore results;
    private final PosIdempotencyStore idempotency;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final PosProperties properties;
    private final Clock clock;

    public PosPaymentRequestService(PosTerminalResolver terminals, MerchantLookup merchants, BarcodeStore barcodes,
            PaymentTransactionRepository payments, CardRecommender recommender,
            RecommendationSnapshotStore snapshots, DatabaseRecommendationResultStore results,
            PosIdempotencyStore idempotency, RedisLocks locks, TransactionTemplate transaction,
            ObjectMapper objectMapper, PosProperties properties, Clock clock) {
        this.terminals = terminals;
        this.merchants = merchants;
        this.barcodes = barcodes;
        this.payments = payments;
        this.recommender = recommender;
        this.snapshots = snapshots;
        this.results = results;
        this.idempotency = idempotency;
        this.locks = locks;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public PosPaymentRequestResponse create(String posKey, PosPaymentRequest request, String idempotencyKey) {
        PosProperties.Terminal terminal = terminals.resolve(posKey, request.getPosCode());
        String key = IdempotencyKeys.require(idempotencyKey);
        PaymentTerms terms = request.toTerms();
        Merchant merchant = merchants.findByBrandCode(terminal.brandCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND));
        if (!merchant.merchantId().equals(request.getMerchantId())) {
            throw new BusinessException(ErrorCode.MERCHANT_NOT_FOUND, "이 POS에서 결제할 수 없는 가맹점입니다.");
        }
        PosBasket.Snapshot basket = PosBasket.validate(terminal.brandCode(), request.getItems(), terms.amount());
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
            // Redis에 기록이 없어도(기억 실패·재시작·보관 종료) 같은 키로 커밋된 결제가 있으면 그 결과다
            Optional<PaymentTransaction> committed = payments.findByRequestKey(key);
            if (committed.isPresent()) {
                return replayCommitted(terminal, key, committed.get(), fingerprint);
            }
            // DB(마이크로초)에 저장한 값과 응답의 confirmDeadline이 첫 응답과 재전송에서 같도록 미리 자른다
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Barcode barcode = requireIssued(request.getBarcodeToken(), now);
            PaymentTransaction payment = createPayment(terminal, merchant, barcode, request, terms, key,
                    fingerprint, now, basket);
            remember(terminal, key, fingerprint, payment);
            return response(payment, request.getOrderNo());
        }
    }

    /** 결제 저장 → 바코드 전환 → 추천을 한 트랜잭션으로. 바코드 전환이 실패하면 추천을 계산하지 않고 저장한 결제도 사라진다. */
    private PaymentTransaction createPayment(PosProperties.Terminal terminal, Merchant merchant, Barcode barcode,
            PosPaymentRequest request, PaymentTerms terms, String key, String fingerprint, Instant now, PosBasket.Snapshot basket) {
        LocalDateTime requestedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        MerchantContextSnapshot context = new MerchantContextSnapshot(MerchantContextSnapshot.CURRENT_VERSION,
                terminal.posCode(), merchant.brandCode(), merchant.name(), merchant.categoryCode(),
                terminal.channel(), terminal.facilityType(), request.getOrderNo(), terms.amount(), terms.plan(),
                terms.isInstallment() ? terms.installmentMonths() : null, basket);
        // 바코드를 전환했을 수 있는 결제 번호. 스캔 응답을 못 받아도 되돌릴 수 있게 스캔 전에 적는다(되돌리기는 이 번호일 때만 바꾼다)
        Long[] scanning = new Long[1];
        // 추천에 필요한 조회를 마친 뒤 결제 저장 트랜잭션을 시작한다.
        var prepared = recommender.prepare(RecommendationRequest.posPurchase(null, barcode.userId(), terms,
                new MerchantContext(merchant.merchantId(), merchant.categoryCode(), terminal.channel().name(),
                        terminal.facilityType() == null ? null : terminal.facilityType().name()), requestedAt, basket));
        try {
            return transaction.execute(status -> {
                PaymentTransaction saved = payments.saveAndFlush(PaymentTransaction.request(barcode.userId(),
                        merchant.merchantId(), terminal.channel(), terminal.facilityType(),
                        context.toJson(objectMapper), key, HexFormat.of().parseHex(fingerprint), terms.amount(),
                        terms.installmentMonthsOrOne(), requestedAt));
                scanning[0] = saved.getId();
                ScanResult scan = barcodes.scan(barcode.token(), barcode.userId(), saved.getId(), now,
                        properties.scannedRetention());
                if (scan != ScanResult.SCANNED) {
                    scanning[0] = null;
                    throw scanFailure(scan);
                }
                RecommendationResult result = prepared.apply(saved.getId());
                // 접수 당시 추천을 결제 행에 남긴다(S15P31E103-293). 결제와 같은 트랜잭션이라 실패하면 결제도 남지 않는다.
                // 재전송은 여기까지 오지 않고, 승인 때 재계산은 이 값을 덮어쓰지 않는다. Redis는 롤백되지 않으므로 DB를 먼저 쓴다.
                // 주의: JDBC로 version을 올리므로 이 트랜잭션에서 이 뒤에 saved 엔티티를 고치면 낙관적 잠금 충돌이 난다(읽기만 한다)
                results.save(saved.getUserId(), saved.getId(), result);
                snapshots.save(saved.getId(), result, properties.snapshotTtl());
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains(REQUEST_KEY_CONSTRAINT)) {
                // 같은 키의 다른 요청이 먼저 커밋했다(다른 POS가 같은 키를 쓴 경우 등). 내용이 같으면 그 결과, 다르면 409
                return findCommitted(key)
                        .map(existing -> sameRequest(existing, fingerprint, key))
                        .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
            }
            compensate(barcode.token(), scanning[0], key);
            throw e;
        } catch (BusinessException e) {
            compensate(barcode.token(), scanning[0], key);
            throw e;
        } catch (RuntimeException e) {
            // 커밋 응답만 잃은 경우(DB에는 커밋됨)라면 결제 행이 있다. 그때는 실패가 아니라 그 결제가 결과다
            Optional<PaymentTransaction> committed = findCommittedQuietly(key);
            if (committed.isPresent() && fingerprint.equals(hex(committed.get()))) {
                log.warn("결제 요청 트랜잭션이 예외로 끝났지만 커밋돼 있음, 그 결제로 응답 paymentId={}", committed.get().getId(), e);
                return committed.get();
            }
            compensate(barcode.token(), scanning[0], key);
            throw e;
        }
    }

    /**
     * 커밋 실패 등으로 결제 행 없이 바코드만 SCANNED로 남는 것을 막는다. 결제 행이 실제로 없는지 DB에서 다시 확인하고, 없을 때만 되돌린다
     * (커밋 결과를 모르는데 되돌리면 같은 바코드로 REQUESTED 결제가 둘 생길 수 있다). 되돌리지 못해도 요청 실패는 그대로 알린다.
     */
    private void compensate(String token, Long paymentId, String key) {
        if (paymentId == null) {
            return;
        }
        try {
            if (findCommitted(key).isPresent()) {
                log.warn("결제 행이 커밋돼 있어 바코드 전환을 되돌리지 않음 paymentId={}", paymentId);
                return;
            }
            barcodes.revertScan(token, paymentId);
            snapshots.delete(paymentId);
        } catch (RuntimeException e) {
            log.warn("바코드 전환 되돌리기 실패(커밋 여부 확인 불가 포함) paymentId={}", paymentId, e);
        }
    }

    /** 실패한 트랜잭션과 별개의 짧은 트랜잭션에서 같은 키의 결제를 찾는다. */
    private Optional<PaymentTransaction> findCommitted(String key) {
        return payments.findByRequestKey(key);
    }

    private Optional<PaymentTransaction> findCommittedQuietly(String key) {
        try {
            return findCommitted(key);
        } catch (RuntimeException e) {
            log.warn("결제 요청 커밋 여부를 확인하지 못함", e);
            return Optional.empty();
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

    /** 결과를 Redis에 기억한다. 실패해도 결제는 커밋됐고 같은 키 재전송은 DB 대조로 처리하므로 201을 그대로 돌려준다. */
    private void remember(PosProperties.Terminal terminal, String key, String fingerprint, PaymentTransaction payment) {
        try {
            idempotency.remember(terminal.posCode(), key, new PosIdempotencyStore.Previous(fingerprint, payment.getId()));
        } catch (RuntimeException e) {
            log.warn("POS 멱등 결과 기억 실패(재전송은 DB로 대조) paymentId={}", payment.getId(), e);
        }
    }

    /**
     * 처음 결과를 다시 보여 준다. 같은 키에 다른 본문이면 409.
     * 상태는 처음 응답 때가 아니라 지금 DB 값이다(승인 뒤 재전송이면 APPROVED). POS는 결과 조회로 상태를 다시 보므로 그대로 둔다.
     */
    private PosPaymentRequestResponse replay(PosIdempotencyStore.Previous previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        PaymentTransaction payment = payments.findById(previous.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        return response(payment, orderNo(payment));
    }

    /** Redis에 기록 없이 DB에서 찾은 같은 키의 결제. 내용이 같으면 처음 결과로 응답하고 Redis에도 다시 기억한다. */
    private PosPaymentRequestResponse replayCommitted(PosProperties.Terminal terminal, String key,
            PaymentTransaction payment, String fingerprint) {
        PaymentTransaction same = sameRequest(payment, fingerprint, key);
        remember(terminal, key, fingerprint, same);
        return response(same, orderNo(same));
    }

    private PaymentTransaction sameRequest(PaymentTransaction payment, String fingerprint, String key) {
        if (!fingerprint.equals(hex(payment))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        log.info("같은 Idempotency-Key 재전송을 DB 기록으로 응답 paymentId={}", payment.getId());
        return payment;
    }

    private static String hex(PaymentTransaction payment) {
        return payment.getRequestFingerprint() == null ? null : HexFormat.of().formatHex(payment.getRequestFingerprint());
    }

    private String orderNo(PaymentTransaction payment) {
        return MerchantContextSnapshot.of(payment, objectMapper).map(MerchantContextSnapshot::orderNo).orElse(null);
    }

    private PosPaymentRequestResponse response(PaymentTransaction payment, String orderNo) {
        return new PosPaymentRequestResponse(payment.getId(), orderNo, payment.getStatus(),
                payment.getRequestedAt().plus(properties.confirmWindow()));
    }

    /** 최초 요청의 불변 내용. 같은 키로 다른 내용이 오면 409 IDEMPOTENCY_KEY_REUSED. posCode가 들어 있어 다른 POS가 같은 키를 쓰면 다른 내용이다. */
    static String fingerprint(PosProperties.Terminal terminal, PosPaymentRequest request, PaymentTerms terms) {
        String canonical = String.join("\n",
                terminal.posCode(),
                String.valueOf(request.getMerchantId()),
                request.getBarcodeToken(),
                Long.toString(terms.amount()),
                terms.plan().name(),
                terms.isInstallment() ? Integer.toString(terms.installmentMonths()) : "",
                request.getOrderNo() == null ? "" : request.getOrderNo()) + PosBasket.fingerprint(request.getItems());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
