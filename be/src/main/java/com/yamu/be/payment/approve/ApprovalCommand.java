package com.yamu.be.payment.approve;

import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 승인 실행에 넘기는 값. 결제 행은 이미 PROCESSING으로 저장돼 있다.
 *
 * @param terms       POS가 정한 결제 조건(DB에 보존된 값). 앱 본문의 값이 아니다
 * @param card        승인할 카드
 * @param candidate   추천 후보였으면 그 후보(예상 혜택·규칙 버전). 추천 밖의 카드를 직접 골랐으면 null
 * @param requestedAt POS 요청 시각(UTC)
 * @param now         승인 처리 시각(UTC)
 * @param attemptNo   이 결제의 몇 번째 승인 시도인지(1부터). 거절 뒤 재승인이면 2 이상
 */
public record ApprovalCommand(Long paymentId, Long userId, PaymentTerms terms, CardSummary card, Candidate candidate,
        LocalDateTime requestedAt, LocalDateTime now, int attemptNo) {

    public ApprovalCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(now, "now");
    }

    /**
     * 승인액: 결제 금액 − 추천 후보의 즉시할인({@link PaymentTerms#chargedAmount}). 체크카드는 출금액, 신용카드는 청구 원금이다.
     * 후보가 아니면(추천 밖의 카드를 직접 고름) 즉시할인 0원이라 결제 금액이다. 결제 화면이 보여 준 금액과 같은 계산이다.
     */
    public long chargedAmount() {
        long instantDiscount = candidate != null ? candidate.instantDiscount() : 0;
        return terms.chargedAmount(instantDiscount);
    }
}
