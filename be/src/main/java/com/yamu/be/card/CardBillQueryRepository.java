package com.yamu.be.card;

import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * BE 1(카드 관리·계좌 목록)이 청구서(card_bill, BE 2)를 읽는 조회 전용 Repository.
 *
 * <p>BE 2 {@code CardBillRepository}를 고치지 않고 BE 1 쪽에 따로 둔다. 같은 엔티티({@link CardBill})를 쓰므로 상태 이름이
 * 바뀌면 컴파일에서, 칸 이름이 바뀌면 앱이 뜰 때(조회 메서드 검사) 바로 드러난다. 저장 메서드는 열지 않는다
 * ({@code JpaRepository}가 아니라 {@code Repository}를 상속).
 *
 * <p>카드 해제와 회원 탈퇴는 남은 청구가 있어도 막지 않는다. 카드 해제는 청구를 그대로 출금하고(S15P31E103-562), 회원 탈퇴는
 * 남은 청구·환급을 닫는다({@code WithdrawnMemberBillingCloser}, S15P31E103-443).
 */
public interface CardBillQueryRepository extends Repository<CardBill, Long> {

    /** 카드들 중 그 상태의 청구서. 빈 목록은 넘기지 않는다. */
    List<CardBill> findByUserCardIdInAndStatus(Collection<Long> userCardIds, BillStatus status);

    /** 이 카드의 그 상태 청구서 중 출금일이 가장 가까운 것. */
    Optional<CardBill> findFirstByUserCardIdAndStatusOrderByWithdrawalDateAscIdAsc(Long userCardId, BillStatus status);

    /** 이 사용자의 계좌들에서 나갈 그 상태 청구서(청구 확정 당시 출금 계좌 기준), 출금일 순. 빈 목록은 넘기지 않는다. */
    List<CardBill> findByUserIdAndLinkedAccountIdInAndStatusOrderByWithdrawalDateAscIdAsc(Long userId,
            Collection<Long> linkedAccountIds, BillStatus status);
}
