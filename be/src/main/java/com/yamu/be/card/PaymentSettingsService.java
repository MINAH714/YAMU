package com.yamu.be.card;

import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.card.dto.PaymentSettingsResponse;
import com.yamu.be.card.dto.PaymentSettingsUpdateRequest;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 설정(노션 API 명세 V2 「결제 설정 조회」「결제 설정 수정」, DEC-004·FR-004·FR-065, S15P31E103-315).
 *
 * <ul>
 *   <li>자동 선택 여부는 {@code app_user.payment_auto_select}, 기본 카드는 {@code user_card.is_default}(카드 수정 API와 같은 값)</li>
 *   <li>자동 선택을 끄려면 기본 카드가 있어야 한다(409 DEFAULT_CARD_REQUIRED). 기본 카드는 내 ACTIVE 카드만(400 CARD_NOT_ELIGIBLE)</li>
 *   <li>임시: 끈 상태에서 카드 수정·해제로 기본 카드가 없어지면 {@link #restoreAutoSelectIfNoDefault}로 다시 켠다.
 *       막으면 기본 카드를 해제할 수 없고, 그대로 두면 결제할 카드가 없어서다</li>
 *   <li>결제할 때 이 값을 읽는 것은 결제 쪽(BE 2) 몫이다. {@link #autoSelect(long)}을 쓴다</li>
 * </ul>
 *
 * <p>수정은 카드 관리와 같이 사용자 행을 잠가(SELECT … FOR UPDATE) 카드 수정·해제와 차례로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettingsService {

    private static final Set<CardRegistrationStatus> REGISTERED =
            EnumSet.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE);

    private final AppUserRepository userRepository;
    private final UserCardRepository cardRepository;

    /**
     * 결제 카드 자동 선택이 켜져 있는지(결제 카드 선택용, BE 2). false면 추천 계산 없이 기본 카드로 결제한다(명세).
     * 사용자가 없으면 명세 기본값 true.
     */
    @Transactional(readOnly = true)
    public boolean autoSelect(long userId) {
        return userRepository.findById(userId).map(AppUser::isPaymentAutoSelect).orElse(true);
    }

    @Transactional(readOnly = true)
    public PaymentSettingsResponse get(AuthUser me) {
        AppUser user = userRepository.findById(me.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return view(user, cardRepository.findOrdered(user.getId(), REGISTERED));
    }

    /** 보낸 칸만 바꾼다. 바뀐 값은 다음 결제 요청부터 적용된다(진행 중 결제 요청은 결제 쪽이 이미 고른 카드). */
    @Transactional
    public PaymentSettingsResponse update(AuthUser me, PaymentSettingsUpdateRequest request) {
        AppUser user = userRepository.findByIdForUpdate(me.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        List<UserCard> cards = cardRepository.findOrdered(user.getId(), REGISTERED);
        UserCard current = cards.stream().filter(UserCard::isDefaultCard).findFirst().orElse(null);

        UserCard target = current;
        if (request.changesDefaultCard()) {
            target = request.defaultUserCardId() == null ? null : eligible(cards, request.defaultUserCardId());
        }
        boolean autoSelect = request.autoSelect() == null ? user.isPaymentAutoSelect() : request.autoSelect();
        if (!autoSelect && target == null) {
            throw new BusinessException(ErrorCode.DEFAULT_CARD_REQUIRED);
        }

        if (target != current) {
            // 사용자당 기본 카드 1장은 DB UNIQUE가 지키므로 기존 기본 카드를 먼저 풀어 반영한다(카드 수정 API와 같은 순서).
            if (current != null) {
                current.markDefault(false);
                cardRepository.saveAndFlush(current);
            }
            if (target != null) {
                target.markDefault(true);
                cardRepository.saveAndFlush(target);
            }
        }
        user.changePaymentAutoSelect(autoSelect);
        userRepository.saveAndFlush(user);
        return view(user, cards);
    }

    /**
     * 임시: 자동 선택이 꺼져 있는데 기본 카드가 없어졌으면 다시 켠다. 카드 수정·해제가 사용자 행을 잠근 트랜잭션 안에서 부른다.
     *
     * @return 다시 켰으면 true
     */
    boolean restoreAutoSelectIfNoDefault(AppUser lockedUser) {
        if (lockedUser.isPaymentAutoSelect()) {
            return false;
        }
        boolean hasDefault = cardRepository.findOrdered(lockedUser.getId(), REGISTERED).stream()
                .anyMatch(UserCard::isDefaultCard);
        if (hasDefault) {
            return false;
        }
        lockedUser.changePaymentAutoSelect(true);
        userRepository.saveAndFlush(lockedUser);
        return true;
    }

    /** 기본 카드로 고를 수 있는 내 카드. 남의 카드·없는 카드·자동 선택에서 뺀 카드는 400(명세 CARD_NOT_ELIGIBLE). */
    private static UserCard eligible(List<UserCard> cards, Long userCardId) {
        return cards.stream()
                .filter(card -> card.getId().equals(userCardId))
                .filter(card -> card.getRegistrationStatus() == CardRegistrationStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_ELIGIBLE,
                        "기본 카드는 자동 선택에 포함된 내 카드만 고를 수 있습니다."));
    }

    private static PaymentSettingsResponse view(AppUser user, List<UserCard> cards) {
        Long defaultUserCardId = cards.stream().filter(UserCard::isDefaultCard).map(UserCard::getId)
                .findFirst().orElse(null);
        LocalDateTime updatedAt = Stream.concat(Stream.of(user.getUpdatedAt()), cards.stream().map(UserCard::getUpdatedAt))
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        return new PaymentSettingsResponse(user.isPaymentAutoSelect(), defaultUserCardId,
                ids(cards, CardRegistrationStatus.ACTIVE), ids(cards, CardRegistrationStatus.INACTIVE),
                cards.stream().map(UserCard::getId).toList(), updatedAt);
    }

    private static List<Long> ids(List<UserCard> cards, CardRegistrationStatus status) {
        return cards.stream().filter(card -> card.getRegistrationStatus() == status).map(UserCard::getId).toList();
    }
}
