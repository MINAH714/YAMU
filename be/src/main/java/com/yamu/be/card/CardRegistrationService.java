package com.yamu.be.card;

import com.yamu.be.account.AccountQueryService;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.card.CardVerificationStore.VerifiedCard;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.card.dto.CardLookupRequest;
import com.yamu.be.card.dto.CardLookupResponse;
import com.yamu.be.card.dto.CardRegisterRequest;
import com.yamu.be.card.dto.CardRegisterResponse;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 확인 → 카드 등록. 명세: 노션 API 명세서 V2 「카드 확인 (카드사·상품 식별)」「카드 등록」.
 *
 * <pre>
 * 1. 확인: 카드번호 조회 해시로 서버가 미리 넣어 둔 사용자 명의 카드(user_card PENDING)를 찾는다 → verificationToken(5분)
 * 2. 등록: 토큰 + 출금 계좌 확인 → 본인 인증(CARD_REGISTER) → 토큰 소비 → ACTIVE
 * </pre>
 *
 * <p>카드번호 원문은 이 메서드 안에서만 쓰고 DB·Redis·응답·로그에 남기지 않는다(NFR-003). 응답은 앞 4자리·끝 4자리만 보인다.
 * CVC·카드 비밀번호는 받지 않는다. 이체 기능이 없어 출금 동의는 받지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CardRegistrationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\d{16}");
    private static final int MAX_PAYMENT_DAY = 27;

    private final UserCardRepository cardRepository;
    private final CardProductRepository productRepository;
    private final LinkedAccountRepository accountRepository;
    private final AccountQueryService accountQueryService;
    private final StepUpService stepUpService;
    private final CardVerificationStore verificationStore;
    private final CardHistoryReader historyReader;
    private final CardRegisterProperties properties;
    private final LookupHasher lookupHasher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CardLookupResponse lookup(AuthUser me, CardLookupRequest request) {
        String cardNumber = request.cardNumber();
        if (!CARD_NUMBER.matcher(cardNumber).matches()) {
            throw new BusinessException(ErrorCode.INVALID_CARD_NUMBER);
        }
        // 유효기간 칸이 없어 지난 유효기간만 거절한다(그 달 말일까지 유효).
        YearMonth expiry = YearMonth.of(2000 + Integer.parseInt(request.expiryYear()),
                Integer.parseInt(request.expiryMonth()));
        if (expiry.isBefore(YearMonth.from(LocalDate.now(clock.withZone(KST))))) {
            throw new BusinessException(ErrorCode.CARD_VERIFICATION_FAILED);
        }
        // 남의 카드는 해시가 같아도 user_id가 달라 찾지 않는다. 명의가 다르면 "없는 카드"와 같게 본다.
        UserCard card = cardRepository.findByUserIdAndCardNoHash(me.userId(), lookupHasher.card(cardNumber))
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND));
        if (card.getRegistrationStatus().isRegistered()) {
            throw new BusinessException(ErrorCode.CARD_ALREADY_REGISTERED);
        }
        CardProduct product = productOf(card);
        boolean credit = product.getCardType() == CardType.CREDIT;

        String masked = mask(cardNumber);
        String token = verificationStore.issue(new VerifiedCard(me.userId(), card.getId(), masked));
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(properties.verificationTtl())
                .truncatedTo(ChronoUnit.SECONDS);
        CardIssuers.Issuer issuer = CardIssuers.of(product.getIssuerCode());
        return new CardLookupResponse(token, expiresAt,
                new CardLookupResponse.IssuerResponse(issuer.code(), issuer.name(), issuer.color(),
                        credit ? product.getBillingCutoffDays() : null),
                new CardLookupResponse.Product(product.getId(), product.getProductName(), product.getCardType()),
                masked, true, historyReader.hasPriorHistory(card.getId()),
                credit ? null : presetAccount(card), false);
    }

    /**
     * 확인한 카드를 등록한다. 입력·계좌를 다 확인한 뒤 본인 인증 토큰을 쓰고, 그다음에 확인 토큰을 소비한다.
     * 입력이 틀려 실패하면 두 토큰이 남아 있어 고쳐서 다시 보낼 수 있다.
     */
    @Transactional
    public CardRegisterResponse register(AuthUser me, CardRegisterRequest request, String stepUpToken) {
        VerifiedCard verified = verificationStore.find(request.verificationToken(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        UserCard card = cardRepository.findByIdAndUserId(verified.userCardId(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
        if (!card.isRegistrable()) {
            throw new BusinessException(ErrorCode.CARD_ALREADY_REGISTERED);
        }
        CardProduct product = productOf(card);
        boolean credit = product.getCardType() == CardType.CREDIT;

        Integer paymentDay = null;
        Long accountId;
        if (credit) {
            paymentDay = request.paymentDay();
            if (paymentDay == null || paymentDay < 1 || paymentDay > MAX_PAYMENT_DAY) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_DAY);
            }
            accountId = request.settlementAccountId();
        } else {
            accountId = request.linkedAccountId();
        }
        if (accountId == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_REQUIRED);
        }
        // 남의 계좌·연결 해제한 계좌는 다른 기능과 같이 404(있는지 드러내지 않는다).
        accountQueryService.requireConnectedAccount(me.userId(), accountId);

        stepUpService.requireStepUp(me, StepUpPurpose.CARD_REGISTER, stepUpToken);
        verificationStore.consume(request.verificationToken(), me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));

        LocalDateTime now = LocalDateTime.now(clock);
        String alias = request.nickname() == null || request.nickname().isBlank() ? null : request.nickname().strip();
        card.register(accountId, paymentDay, alias, now);
        cardRepository.saveAndFlush(card);

        CardIssuers.Issuer issuer = CardIssuers.of(product.getIssuerCode());
        CardRegisterResponse.Billing billing = credit
                ? new CardRegisterResponse.Billing(paymentDay, product.getBillingCutoffDays(), accountId, null)
                : null;
        return new CardRegisterResponse(card.getId(), alias == null ? product.getProductName() : alias,
                new CardRegisterResponse.Issuer(issuer.code(), issuer.name(), issuer.color()),
                product.getProductName(), product.getCardType(), verified.maskedNumber(),
                card.getRegistrationStatus(), false, accountId, billing,
                historyReader.initialPerformance(card.getId(), LocalDate.now(clock.withZone(KST))),
                card.getRegisteredAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private CardProduct productOf(UserCard card) {
        return productRepository.findById(card.getCardProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ISSUER_NOT_SUPPORTED));
    }

    /** 체크카드에 시드로 미리 정해 둔 연결 계좌. 없으면 null. */
    private CardLookupResponse.LinkedAccount presetAccount(UserCard card) {
        if (card.getLinkedAccountId() == null) {
            return null;
        }
        return accountRepository.findByIdAndUserId(card.getLinkedAccountId(), card.getUserId())
                .map(a -> new CardLookupResponse.LinkedAccount(a.getId(), a.getBankCode(),
                        a.getAccountLast4() == null ? null : "****-****-****-" + a.getAccountLast4(),
                        a.isConnected()))
                .orElse(null);
    }

    /** "9410123456785678" → "9410-****-****-5678" */
    private static String mask(String cardNumber) {
        return cardNumber.substring(0, 4) + "-****-****-" + cardNumber.substring(12);
    }
}
