package com.yamu.be.card;

import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.repository.Repository;

/**
 * BE 1(카드 관리·계좌 목록·회원 탈퇴)이 청구서(card_bill, BE 2)를 읽는 조회 전용 Repository.
 *
 * <p>BE 2 {@code CardBillRepository}를 고치지 않고 BE 1 쪽에 따로 둔다. 같은 엔티티({@link CardBill})를 쓰므로 상태 이름이
 * 바뀌면 컴파일에서, 칸 이름이 바뀌면 앱이 뜰 때(조회 메서드 검사) 바로 드러난다. 저장 메서드는 열지 않는다
 * ({@code JpaRepository}가 아니라 {@code Repository}를 상속).
 */
public interface CardBillQueryRepository extends Repository<CardBill, Long> {

    /**
     * 끝난 청구. 이 상태가 아닌 청구(ACCUMULATING 포함)가 하나라도 있으면 카드 해제(CARD_HAS_OPEN_BILL)와
     * 탈퇴(BILL_OUTSTANDING)를 막는다. 막을 상태를 나열하지 않고 끝난 상태만 나열해, 나중에 생길 상태도 기본으로 막는다.
     */
    Set<BillStatus> SETTLED = Set.of(BillStatus.PAID, BillStatus.CANCELED);

    /** 카드들 중 그 상태의 청구서. 빈 목록은 넘기지 않는다. */
    List<CardBill> findByUserCardIdInAndStatus(Collection<Long> userCardIds, BillStatus status);

    /** 이 카드의 청구서 중 주어진 상태가 아닌 것, 번호 순. */
    List<CardBill> findByUserCardIdAndStatusNotInOrderByIdAsc(Long userCardId, Collection<BillStatus> statuses);

    /** 이 사용자의 청구서 중 주어진 상태가 아닌 것, 번호 순. */
    List<CardBill> findByUserIdAndStatusNotInOrderByIdAsc(Long userId, Collection<BillStatus> statuses);

    /** 이 카드의 그 상태 청구서 중 출금일이 가장 가까운 것. */
    Optional<CardBill> findFirstByUserCardIdAndStatusOrderByWithdrawalDateAscIdAsc(Long userCardId, BillStatus status);

    /** 이 사용자의 계좌들에서 나갈 그 상태 청구서(청구 확정 당시 출금 계좌 기준), 출금일 순. 빈 목록은 넘기지 않는다. */
    List<CardBill> findByUserIdAndLinkedAccountIdInAndStatusOrderByWithdrawalDateAscIdAsc(Long userId,
            Collection<Long> linkedAccountIds, BillStatus status);
}
