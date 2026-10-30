package com.yamu.be.card;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.auth.LoginRateLimiter;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.card.CardVerificationStore.VerifiedCard;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.card.dto.CardLookupRequest;
import com.yamu.be.card.dto.CardLookupResponse;
import com.yamu.be.card.dto.CardRegisterRequest;
import com.yamu.be.card.dto.CardRegisterResponse;
import com.yamu.be.card.dto.CardRegisterResponse.InitialPerformance;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 확인 → 카드 등록. 명세: 노션 API 명세서 V2 「카드 확인 (카드사·상품 식별)」「카드 등록」.
 *
 * <pre>
 * 1. 확인: 카드번호 조회 해시로 서버가 미리 넣어 둔 사용자 명의 카드(user_card PENDING)를 찾는다 → verificationToken(5분)
 * 2. 등록: 사용자 행 잠금 → 토큰 + 카드사가 정한 계좌가 연결됐는지 확인 → 본인 인증(CARD_REGISTER) → 토큰 소비 → ACTIVE
 * </pre>
 *
 * <p>출금 계좌와 결제일은 카드사가 정한다(10/30 결정). 실제 카드도 카드사가 카드마다 출금 계좌 하나와 결제일을 정해 두고,
 * 제3자 앱은 그 값을 읽어 보여 줄 뿐 고르게 하지 않는다. YAMU에서는 시드가 카드사 역할로 카드 행에 계좌(은행 코드·조회 해시·끝 4자리)와
 * 결제일을 적어 두고, 등록 때 사용자가 연결한 계좌 중 같은 계좌를 찾아 붙인다. 시드 때는 linked_account 행이 아직 없어
 * linked_account_id로 바로 가리킬 수 없기 때문이다. 등록 뒤 결제일·결제계좌 변경은 카드 관리가 맡는다(FR-067).
 *
 * <p>카드사 정지·만료 카드, 모르는 카드사의 카드는 확인·등록 모두에서 거절한다. 카드 확인은 사용자별로 1분 호출 수를 센다.
 *
 * <p>카드번호 원문은 이 메서드 안에서만 쓰고 DB·Redis·응답·로그에 남기지 않는다(NFR-003). 응답은 앞 4자리·끝 4자리만 보인다.
 * CVC·카드 비밀번호는 받지 않는다. 이체 기능이 없어 출금 동의는 받지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardRegistrationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\d{16}");

    private final AppUserRepository userRepository;
    private final UserCardRepository cardRepository;
    private final CardProductRepository productRepository;
    private final LinkedAccountRepository accountRepository;
    private final StepUpService stepUpService;
    private final CardVerificationStore verificationStore;
    private final CardHistoryReader historyReader;
    private final CardRegisterProperties properties;
    private final LookupHasher lookupHasher;
    private final LoginRateLimiter rateLimiter;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CardLookupResponse lookup(AuthUser me, CardLookupRequest request) {
        // 형식 오류도 센다. 카드번호를 바꿔 가며 찾아보는 것 자체를 막으려는 것이라서.
        rateLimiter.checkCardLookup(me.userId(), properties.lookupPerMinute());
        String cardNumber = request.cardNumber();
        if (!CARD_NUMBER.matcher(cardNumber).matches()) {
            throw new BusinessException(ErrorCode.INVALID_CARD_NUMBER);
        }
        // 유효기간 칸이 없어 지난 유효기간만 거절한다(그 달 말일까지 유효).
        YearMonth expiry = YearMonth.of(2000 + Integer.parseInt(request.expiryYear()),
                Integer.parseInt(request.expiryMonth()));
        if (isExpired(expiry, clock)) {
            throw new BusinessException(ErrorCode.CARD_VERIFICATION_FAILED, Map.of("reason", "EXPIRY_PASSED"));
        }
        // 남의 카드는 해시가 같아도 user_id가 달라 찾지 않는다. 명의가 다르면 "없는 카드"와 같게 본다.
        UserCard card = cardRepository.findByUserIdAndCardNoHash(me.userId(), lookupHasher.card(cardNumber))
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));
        if (card.getRegistrationStatus().isRegistered()) {
            throw new BusinessException(ErrorCode.CARD_ALREADY_REGISTERED);
        }
        requireIssuerUsable(card);
        CardProduct product = productOf(card);
        CardIssuers.Issuer issuer = issuerOf(product);
        boolean credit = product.getCardType() == CardType.CREDIT;
        requireIssuerSettings(card, credit);
        // 아직 연결하지 않은 계좌여도 확인은 된다. 화면에 "이 계좌를 먼저 연결하세요"를 보여 주고, 등록에서 409로 막는다.
        Optional<LinkedAccount> linked = connectedIssuerAccount(card);

        String masked = mask(cardNumber);
        String token = verificationStore.issue(new VerifiedCard(me.userId(), card.getId(), masked));
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(properties.verificationTtl())
                .truncatedTo(ChronoUnit.SECONDS);
        return new CardLookupResponse(token, expiresAt,
                new CardLookupResponse.IssuerResponse(issuer.code(), issuer.name(), issuer.color(),
                        credit ? product.getBillingCutoffDays() : null),
                new CardLookupResponse.Product(product.getId(), product.getProductName(), product.getCardType()),
                masked, true, historyReader.hasPriorHistory(card.getId()),
                new CardLookupResponse.IssuerAccount(card.getIssuerAccountBankCode(),
                        maskAccount(card.getIssuerAccountLast4()), linked.map(LinkedAccount::getId).orElse(null),
                        linked.isPresent()),
                credit ? card.getPaymentDay() : null, false);
    }

    /**
     * 확인한 카드를 등록한다. 카드사가 정한 계좌가 연결됐는지까지 다 확인한 뒤 본인 인증 토큰을 쓰고, 그다음에 확인 토큰을 소비한다.
     * 계좌를 아직 연결하지 않아 실패하면 두 토큰이 남아 있어, 계좌를 연결하고 다시 보낼 수 있다(확인 토큰 5분 안).
     *
     * <p>먼저 사용자 행을 잠근다(기기 등록·카드 관리와 같은 잠금 순서). 카드 확인을 두 번 해 토큰이 둘이어도
     * 등록은 차례로 처리되어, 늦은 쪽은 카드가 이미 ACTIVE라 409 CARD_ALREADY_REGISTERED다.
     */
    @Transactional
    public CardRegisterResponse register(AuthUser me, CardRegisterRequest request, String stepUpToken) {
        userRepository.findByIdForUpdate(me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        VerifiedCard verified = verificationStore.find(request.verificationToken(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        UserCard card = cardRepository.findByIdAndUserId(verified.userCardId(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        if (!card.isRegistrable()) {
            throw new BusinessException(ErrorCode.CARD_ALREADY_REGISTERED);
        }
        // 확인 뒤 5분 사이에 카드사가 정지·만료했을 수 있어 다시 본다.
        requireIssuerUsable(card);
        CardProduct product = productOf(card);
        CardIssuers.Issuer issuer = issuerOf(product);
        boolean credit = product.getCardType() == CardType.CREDIT;

        requireIssuerSettings(card, credit);
        Long accountId = connectedIssuerAccount(card).map(LinkedAccount::getId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_LINKED, Map.of(
                        "bankCode", card.getIssuerAccountBankCode(), "accountLast4", card.getIssuerAccountLast4())));
        // 실패할 수 있는 읽기는 토큰을 쓰기 전에 끝낸다. 이 뒤에서 실패하면 두 토큰이 돌아오지 않는다.
        InitialPerformance performance = historyReader.initialPerformance(card.getId(),
                LocalDate.now(clock.withZone(KST)));

        stepUpService.requireStepUp(me, StepUpPurpose.CARD_REGISTER, stepUpToken);
        verificationStore.consume(request.verificationToken(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));

        LocalDateTime now = LocalDateTime.now(clock);
        String alias = request.nickname();
        card.register(accountId, alias, now);
        try {
            cardRepository.saveAndFlush(card);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 사용자 행 잠금을 거치지 않는 다른 경로와 겹친 경우의 안전망.
            throw new BusinessException(ErrorCode.CARD_ALREADY_REGISTERED);
        }

        CardRegisterResponse.Billing billing = credit
                ? new CardRegisterResponse.Billing(card.getPaymentDay(), product.getBillingCutoffDays(), accountId, null)
                : null;
        return new CardRegisterResponse(card.getId(), alias == null ? product.getProductName() : alias,
                new CardRegisterResponse.Issuer(issuer.code(), issuer.name(), issuer.color()),
                product.getProductName(), product.getCardType(), verified.maskedNumber(),
                card.getRegistrationStatus(), false, accountId, billing, performance,
                card.getRegisteredAt().truncatedTo(ChronoUnit.SECONDS));
    }

    /** 유효기간은 그 달 말일까지 유효하다. 한국 날짜 기준으로 이번 달보다 앞이면 지난 것이다. */
    static boolean isExpired(YearMonth expiry, Clock clock) {
        return expiry.isBefore(YearMonth.from(LocalDate.now(clock.withZone(KST))));
    }

    /** 카드사가 정지·만료한 카드는 등록할 수 없다. 명세에 따로 코드가 없어 CARD_VERIFICATION_FAILED에 사유를 붙인다. */
    private static void requireIssuerUsable(UserCard card) {
        CardIssuerStatus status = card.getIssuerStatus();
        if (status == CardIssuerStatus.SUSPENDED || status == CardIssuerStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.CARD_VERIFICATION_FAILED,
                    Map.of("reason", "ISSUER_" + status.name()));
        }
    }

    /** 앱에 이름·색을 보여 줄 수 없는 카드사는 상품 데이터 오류로 보고 지원하지 않는 카드로 거절한다. */
    private static CardIssuers.Issuer issuerOf(CardProduct product) {
        return CardIssuers.find(product.getIssuerCode()).orElseThrow(() -> {
            log.warn("카드사 표에 없는 issuer_code라 카드 확인·등록을 거절합니다: cardProductId={}", product.getId());
            return new BusinessException(ErrorCode.ISSUER_NOT_SUPPORTED);
        });
    }

    /**
     * 카드사(시드)가 정한 결제 정보가 있어야 등록할 수 있다. 계좌가 없거나 신용카드인데 결제일이 없으면 시드 데이터가 덜 들어간 것이라
     * 사용자가 고칠 수 없는 409로 끝낸다(임시 코드, details.missing에 빠진 값).
     */
    private static void requireIssuerSettings(UserCard card, boolean credit) {
        List<String> missing = new ArrayList<>();
        if (!card.hasIssuerAccount()) {
            missing.add("account");
        }
        if (credit && card.getPaymentDay() == null) {
            missing.add("paymentDay");
        }
        if (!missing.isEmpty()) {
            log.warn("카드사 결제 정보가 없는 카드라 확인·등록을 거절합니다: userCardId={}, missing={}", card.getId(), missing);
            throw new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_SET, Map.of("missing", missing));
        }
    }

    /**
     * 카드사가 정한 계좌와 같은(은행 코드 + 조회 해시), 이 카드 주인이 지금 연결해 둔 계좌.
     * 남의 계좌는 user_id가 달라 찾지 않고, 연결 해제한 계좌는 없는 것으로 본다.
     */
    private Optional<LinkedAccount> connectedIssuerAccount(UserCard card) {
        return accountRepository.findByUserIdAndBankCodeAndAccountNoHash(card.getUserId(),
                        card.getIssuerAccountBankCode(), card.getIssuerAccountHash())
                .filter(LinkedAccount::isConnected);
    }

    private CardProduct productOf(UserCard card) {
        return productRepository.findById(card.getCardProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ISSUER_NOT_SUPPORTED));
    }

    /** "6547" → "****-****-****-6547" */
    private static String maskAccount(String last4) {
        return "****-****-****-" + last4;
    }

    /** "9410123456785678" → "9410-****-****-5678" */
    private static String mask(String cardNumber) {
        return cardNumber.substring(0, 4) + "-****-****-" + cardNumber.substring(12);
    }
}
