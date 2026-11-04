package com.yamu.be.payment.recommend;

import com.yamu.be.recommendation.PurchaseItemReader;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 담당이 소유권, 잠금, 중복 요청을 처리한 트랜잭션에서 구매 항목과 추천 결과를 보존한다.
 *
 * <p>{@link #preservePurchaseItem}과 {@link #save}는 JDBC로 결제 행의 {@code version}을 증가시킨다.
 * 호출자의 JPA 영속성 컨텍스트가 같은 행의
 * {@link com.yamu.be.payment.domain.PaymentTransaction}을 이미 관리 중이면 엔티티의
 * {@code @Version}에는 이전 값이 남는다. 이후 그 엔티티의 변경을 flush하면
 * 낙관적 잠금 검사에서 {@link jakarta.persistence.OptimisticLockException}이 발생할 수 있다.
 *
 * <p>두 쓰기 메서드는 같은 결제 행의 엔티티가 관리 중인 트랜잭션에서 호출하지 않는다.
 * 같은 영속성 컨텍스트에서 함께 변경해야 한다면 {@code purchase_item_code}와
 * {@code recommendation_result_json}을 엔티티에 매핑하고 JPA로 갱신한다.
 */
@Repository
@RequiredArgsConstructor
public class DatabaseRecommendationResultStore {
    private final JdbcTemplate jdbc;
    private final RecommendationResultJson codec;
    private final PurchaseItemReader purchaseItems;

    @Transactional(propagation = Propagation.MANDATORY)
    public void preservePurchaseItem(long userId, long paymentId, String code) {
        purchaseItems.validateNewSelection(code);
        int count = jdbc.update("UPDATE payment_transaction SET purchase_item_code=?, version=version+1, updated_at=UTC_TIMESTAMP(6) WHERE payment_transaction_id=? AND user_id=? AND purchase_item_code IS NULL",
                code, paymentId, userId);
        if (count != 1) throw new IllegalStateException("구매 항목 저장 대상 없음 또는 이미 보존됨");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(long userId, long paymentId, RecommendationResult result) {
        int count = jdbc.update("UPDATE payment_transaction SET recommendation_result_json=CAST(? AS JSON), version=version+1, updated_at=UTC_TIMESTAMP(6) WHERE payment_transaction_id=? AND user_id=?",
                codec.encode(result), paymentId, userId);
        if (count != 1) throw new IllegalStateException("추천 결과 저장 대상 없음");
    }

    @Transactional(readOnly = true)
    public Optional<RecommendationResult> find(long userId, long paymentId) {
        var rows = jdbc.query("SELECT recommendation_result_json FROM payment_transaction WHERE payment_transaction_id=? AND user_id=?",
                (rs, index) -> rs.getString(1), paymentId, userId);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(codec.decode(rows.get(0)));
    }
}
