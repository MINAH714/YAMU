package com.yamu.be.card;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.AccountQueryService;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.card.dto.CardDetailResponse;
import com.yamu.be.card.dto.CardListResponse;
import com.yamu.be.card.dto.CardOrderRequest;
import com.yamu.be.card.dto.CardOrderResponse;
import com.yamu.be.card.dto.CardUpdateRequest;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보유 카드 관리. 명세: 노션 API 명세서 V2 「보유 카드 목록 조회」「카드 상세 조회」「카드 정보 수정」
 * 「카드 고정 정렬 순서 저장」「카드 등록 해제」.
 *
 * <p>대상은 등록된 카드(ACTIVE·INACTIVE)다. 등록 전(PENDING) 카드는 사용자에게 없는 카드라 404,
 * 해제(UNREGISTERED)한 카드는 목록·상세에서 빠지고(404) 수정은 409 CARD_UNREGISTERED다.
 *
 * <p>기본 카드·순서처럼 한 사용자의 카드 여러 장을 같이 바꾸는 요청은 사용자 행을 잠가(SELECT … FOR UPDATE) 차례로 처리한다.
 * 실적·청구처럼 다른 파트가 아직 만들지 않은 값은 null로 두고 MR에 적었다.
 */
@Service
@RequiredArgsConstructor
public class CardManagementService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<CardRegistrationStatus> REGISTERED =
            EnumSet.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE);
    private static final int MAX_PAYMENT_DAY = 27;
    /** 결제일은 바꾼 뒤 3개월 안에 다시 바꿀 수 없다(FR-067). */
    private static final int PAYMENT_DAY_LOCK_MONTHS = 3;

    private final UserCardRepository cardRepository;
    private final CardProductRepository productRepository;
    private final LinkedAccountRepository accountRepository;
    private final AccountQueryService accountQueryService;
    private final AccountBalanceService balanceService;
    private final AppUserRepository userRepository;
    private final StepUpService stepUpService;
    private final CardActivityReader activityReader;
    private final PersonalDataCipher cipher;
    private final Clock clock;

    /** @param status ACTIVE·INACTIVE 중 하나만 볼 때. null이면 둘 다 */
    @Transactional(readOnly = true)
    public CardListResponse list(AuthUser me, String status) {
        Set<CardRegistrationStatus> statuses = status == null ? REGISTERED : Set.of(registeredStatus(status, "status"));
        List<UserCard> cards = cardRepository.findOrdered(me.userId(), statuses);
        Map<Long, CardProduct> products = products(cards);
        List<Long> ids = cards.stream().map(UserCard::getId).toList();
        YearMonth month = YearMonth.from(today());
        Map<Long, Long> performances = activityReader.recognizedAmounts(ids, month);
        Set<Long> unpaid = activityReader.cardsWithUnpaidBill(ids);

        List<CardListResponse.CardSummary> summaries = cards.stream().map(card -> {
            CardProduct product = products.get(card.getCardProductId());
            CardIssuers.Issuer issuer = CardIssuers.of(product.getIssuerCode());
            Long recognized = performances.get(card.getId());
            CardListResponse.Performance performance = recognized == null ? null
                    : new CardListResponse.Performance(month.toString(), null, recognized, null, null);
            CardListResponse.BillingSummary billing = product.getCardType() == CardType.CREDIT
                    ? new CardListResponse.BillingSummary(card.getPaymentDay(), null, unpaid.contains(card.getId()))
                    : null;
            return new CardListResponse.CardSummary(card.getId(), nickname(card, product),
                    new CardListResponse.Issuer(issuer.code(), issuer.name(), issuer.color()),
                    product.getProductName(), product.getCardType(), maskedNumber(card),
                    card.getRegistrationStatus(), card.getIssuerStatus(), card.isDefaultCard(), List.of(),
                    performance, billing);
        }).toList();
        return new CardListResponse(summaries, null);
    }

    @Transactional(readOnly = true)
    public CardDetailResponse detail(AuthUser me, Long userCardId) {
        UserCard card = cardRepository.findByIdAndUserId(userCardId, me.userId())
                .filter(c -> c.getRegistrationStatus().isRegistered())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return toDetail(card);
    }

    /**
     * 보낸 필드만 바꾼다. 모든 확인(결제일 범위·3개월 제한·계좌 소유)을 마친 뒤에 바꾸므로 하나라도 거절되면 아무것도 바뀌지 않는다.
     * 결제일을 바꾸면 다음 이용기간부터 적용된다(진행 중 이용기간·확정된 청구서는 청구 쪽이 그대로 둔다).
     */
    @Transactional
    public CardDetailResponse update(AuthUser me, Long userCardId, CardUpdateRequest request) {
        lockUser(me);
        UserCard card = cardRepository.findByIdAndUserId(userCardId, me.userId())
                .filter(c -> c.getRegistrationStatus() != CardRegistrationStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (card.getRegistrationStatus() == CardRegistrationStatus.UNREGISTERED) {
            throw new BusinessException(ErrorCode.CARD_UNREGISTERED);
        }
        CardProduct product = productOf(card);
        boolean credit = product.getCardType() == CardType.CREDIT;
        LocalDateTime now = LocalDateTime.now(clock);

        CardRegistrationStatus status = request.status() == null ? null : registeredStatus(request.status(), "status");
        boolean changesPaymentDay = request.paymentDay() != null && !request.paymentDay().equals(card.getPaymentDay());
        if (request.paymentDay() != null) {
            if (!credit || request.paymentDay() < 1 || request.paymentDay() > MAX_PAYMENT_DAY) {
                throw new BusinessException(ErrorCode.INVALID_PAYMENT_DAY);
            }
            LocalDate changeableFrom = paymentDayChangeableFrom(card);
            if (changesPaymentDay && changeableFrom != null && today().isBefore(changeableFrom)) {
                throw new BusinessException(ErrorCode.PAYMENT_DAY_CHANGE_LOCKED,
                        Map.of("changeableFrom", changeableFrom.toString()));
            }
        }
        if (request.settlementAccountId() != null) {
            if (!credit) {
                throw invalid("settlementAccountId", "결제계좌는 신용카드만 바꿀 수 있습니다.");
            }
            // 남의 계좌·연결 해제한 계좌는 카드 등록과 같이 404(있는지 드러내지 않는다).
            accountQueryService.requireConnectedAccount(me.userId(), request.settlementAccountId());
        }

        if (request.nickname() != null) {
            card.rename(request.nickname().isBlank() ? null : request.nickname().strip());
        }
        if (status != null) {
            card.changeRecommendation(status);
        }
        if (changesPaymentDay) {
            card.changePaymentDay(request.paymentDay(), now);
        }
        if (request.settlementAccountId() != null) {
            card.changeLinkedAccount(request.settlementAccountId());
        }
        if (request.isDefault() != null) {
            changeDefault(me, card, request.isDefault());
        }
        cardRepository.saveAndFlush(card);
        return toDetail(card);
    }

    /**
     * 카드 순서를 통째로 저장한다. 등록된 카드 전체를 빠짐없이 한 번씩 보내야 한다.
     * 두 기기에서 동시에 저장하면 사용자 잠금으로 차례로 처리되고 늦게 온 순서가 남는다.
     */
    @Transactional
    public CardOrderResponse saveOrder(AuthUser me, CardOrderRequest request) {
        lockUser(me);
        List<Long> wanted = request.userCardIds();
        List<UserCard> cards = cardRepository.findOrdered(me.userId(), REGISTERED);
        Map<Long, UserCard> byId = cards.stream().collect(Collectors.toMap(UserCard::getId, Function.identity()));
        if (wanted.contains(null) || wanted.size() != cards.size() || new HashSet<>(wanted).size() != wanted.size()
                || !byId.keySet().containsAll(wanted)) {
            throw new BusinessException(ErrorCode.ORDER_INCOMPLETE);
        }
        for (int i = 0; i < wanted.size(); i++) {
            byId.get(wanted.get(i)).placeAt(i);
        }
        cardRepository.saveAllAndFlush(cards);
        return new CardOrderResponse(List.copyOf(wanted), LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 카드 등록을 해제한다(행은 남김). 카드·청구를 다 확인한 뒤 본인 인증 토큰(CARD_DELETE)을 쓴다.
     * 확정됐지만 출금되지 않은 청구서가 있으면 409 CARD_HAS_OPEN_BILL(명세의 가정)이다.
     */
    @Transactional
    public void unregister(AuthUser me, Long userCardId, String stepUpToken) {
        lockUser(me);
        UserCard card = cardRepository.findByIdAndUserId(userCardId, me.userId())
                .filter(c -> c.getRegistrationStatus().isRegistered())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Long> openBills = activityReader.openBillIds(card.getId());
        if (!openBills.isEmpty()) {
            throw new BusinessException(ErrorCode.CARD_HAS_OPEN_BILL, Map.of("billIds", openBills));
        }
        stepUpService.requireStepUp(me, StepUpPurpose.CARD_DELETE, stepUpToken);
        card.unregister();
        cardRepository.saveAndFlush(card);
    }

    /**
     * 기본 카드를 바꾼다. 사용자당 1장은 DB UNIQUE가 지키므로, 기존 기본 카드를 먼저 풀어 반영한 뒤 새 카드를 지정한다.
     */
    private void changeDefault(AuthUser me, UserCard card, boolean makeDefault) {
        if (!makeDefault) {
            card.markDefault(false);
            return;
        }
        if (card.isDefaultCard()) {
            return;
        }
        List<UserCard> previous = cardRepository.findOrdered(me.userId(), REGISTERED).stream()
                .filter(c -> c.isDefaultCard() && !c.getId().equals(card.getId())).toList();
        previous.forEach(c -> c.markDefault(false));
        cardRepository.saveAllAndFlush(previous);
        card.markDefault(true);
    }

    private CardDetailResponse toDetail(UserCard card) {
        CardProduct product = productOf(card);
        boolean credit = product.getCardType() == CardType.CREDIT;
        CardIssuers.Issuer issuer = CardIssuers.of(product.getIssuerCode());

        CardDetailResponse.LinkedAccount linkedAccount = null;
        CardDetailResponse.Billing billing = null;
        if (credit) {
            CardDetailResponse.NextWithdrawal next = activityReader.nextWithdrawal(card.getId())
                    .map(b -> new CardDetailResponse.NextWithdrawal(b.billId(), b.amount(), b.withdrawalDate(),
                            b.status()))
                    .orElse(null);
            boolean unpaid = activityReader.cardsWithUnpaidBill(List.of(card.getId())).contains(card.getId());
            billing = new CardDetailResponse.Billing(card.getPaymentDay(), paymentDayChangeableFrom(card),
                    card.getLinkedAccountId(), null, null, null, next, unpaid);
        } else if (card.getLinkedAccountId() != null) {
            // 트랜잭션 안이라 금융망은 부르지 않고 계좌 잔액 캐시(10분)에 있으면 보여 준다.
            AccountBalance balance = balanceService.findCachedBalance(card.getUserId(), card.getLinkedAccountId())
                    .orElse(null);
            linkedAccount = accountRepository.findByIdAndUserId(card.getLinkedAccountId(), card.getUserId())
                    .map(a -> new CardDetailResponse.LinkedAccount(a.getId(), a.getBankCode(),
                            a.getAccountLast4() == null ? null : "****-****-****-" + a.getAccountLast4(),
                            balance == null ? null : balance.balance(), balance == null ? null : balance.fetchedAt()))
                    .orElse(null);
        }
        return new CardDetailResponse(card.getId(), nickname(card, product),
                new CardDetailResponse.Issuer(issuer.code(), issuer.name(), issuer.color(),
                        credit ? product.getBillingCutoffDays() : null),
                product.getProductName(), product.getCardType(), maskedNumber(card), null,
                card.getRegistrationStatus(), card.getIssuerStatus(), card.isDefaultCard(), linkedAccount, billing,
                card.getRegisteredAt() == null ? null : card.getRegisteredAt().truncatedTo(ChronoUnit.SECONDS), null);
    }

    /** 직전 결제일 변경일(한국 날짜) + 3개월. 바꾼 적이 없으면 null. */
    private static LocalDate paymentDayChangeableFrom(UserCard card) {
        if (card.getPaymentDayChangedAt() == null) {
            return null;
        }
        return card.getPaymentDayChangedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate()
                .plusMonths(PAYMENT_DAY_LOCK_MONTHS);
    }

    private void lockUser(AuthUser me) {
        userRepository.findByIdForUpdate(me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private Map<Long, CardProduct> products(List<UserCard> cards) {
        Set<Long> ids = cards.stream().map(UserCard::getCardProductId).collect(Collectors.toSet());
        return productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(CardProduct::getId, Function.identity()));
    }

    private CardProduct productOf(UserCard card) {
        return productRepository.findById(card.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("카드 상품이 없습니다: " + card.getCardProductId()));
    }

    /** 카드번호 암호문을 열어 앞 4자리·끝 4자리만 남긴다. 원문은 이 메서드 밖으로 나가지 않는다. */
    private String maskedNumber(UserCard card) {
        String number = cipher.decrypt(card.getCardNoEnc(), CipherField.USER_CARD_NO);
        return number.substring(0, 4) + "-****-****-" + card.getCardLast4();
    }

    private static String nickname(UserCard card, CardProduct product) {
        return card.getCardAlias() == null ? product.getProductName() : card.getCardAlias();
    }

    private static CardRegistrationStatus registeredStatus(String value, String field) {
        try {
            CardRegistrationStatus status = CardRegistrationStatus.valueOf(value);
            if (status.isRegistered()) {
                return status;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 INVALID_REQUEST
        }
        throw invalid(field, "ACTIVE 또는 INACTIVE만 쓸 수 있습니다.");
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }
}
