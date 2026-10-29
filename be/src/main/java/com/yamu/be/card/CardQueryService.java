package com.yamu.be.card;

import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 기능(예정 지출·결제·잔고예보)이 "요청한 사용자의 등록된 카드인지" 확인할 때 쓴다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CardQueryService {

    private final UserCardRepository cardRepository;
    private final CardProductRepository productRepository;

    /**
     * 사용자 본인 소유이고 보유 카드 목록에 보이는(ACTIVE·INACTIVE) 카드를 돌려준다.
     * 없는 번호·남의 카드·등록 전(PENDING)·등록 해제(UNREGISTERED) 카드는 모두 404 RESOURCE_NOT_FOUND다.
     * 카드사 정지·만료는 여기서 거절하지 않고 {@link UserCardRef#issuerStatus()}로 넘긴다.
     */
    public UserCardRef requireRegisteredCard(Long userId, Long userCardId) {
        return findRegisteredCard(userId, userCardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * {@link #requireRegisteredCard}와 같은 규칙이지만, 쓸 수 없는 카드는 404 대신 empty다.
     * 잔고예보처럼 카드 하나가 해제됐다고 전체를 실패시키지 않고 그 항목만 빼야 하는 곳에서 쓴다.
     */
    public Optional<UserCardRef> findRegisteredCard(Long userId, Long userCardId) {
        if (userId == null || userCardId == null) {
            return Optional.empty();
        }
        return cardRepository.findByIdAndUserId(userCardId, userId)
                .filter(c -> c.getRegistrationStatus().isRegistered())
                .map(this::toRef);
    }

    private UserCardRef toRef(UserCard card) {
        CardProduct product = productRepository.findById(card.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("카드 상품이 없습니다: " + card.getCardProductId()));
        boolean credit = product.getCardType() == CardType.CREDIT;
        return new UserCardRef(card.getId(), card.getCardProductId(), card.getLinkedAccountId(),
                card.getCardLast4(), card.getRegistrationStatus(), card.getIssuerStatus(), product.getCardType(),
                credit ? card.getPaymentDay() : null, credit ? product.getBillingCutoffDays() : null);
    }
}
