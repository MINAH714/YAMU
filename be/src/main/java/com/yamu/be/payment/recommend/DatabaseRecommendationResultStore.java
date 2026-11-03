package com.yamu.be.payment.recommend;

import com.yamu.be.recommendation.PurchaseItemReader;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결제 담당이 소유권·잠금·중복 요청을 처리한 트랜잭션 안에서 호출하는 보존 계약. 추천 계산에서는 호출하지 않는다. */
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
