package com.yamu.be.cashflow;

import com.yamu.be.payment.IdempotencyKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 가계부 항목 등록의 Idempotency-Key 처리(S15P31E103-358, 2026-11-09 결정). 키는 선택이다: 보내면 같은 키 재요청은 처음 항목을
 * 돌려주고 다른 내용이면 409, 보내지 않으면 전처럼 매번 새로 만든다. 키는 결제·계좌 연결과 같이 UUID 형식만 받는다.
 *
 * <p>같은 키의 첫 요청 둘이 동시에 오면 둘 다 기존 행을 못 찾고 저장하다가 늦은 쪽이 UNIQUE(uk_cashflow_item_request)에 걸린다.
 * 그때는 앞 요청이 커밋한 뒤라 한 번 더 부르면 그 항목을 찾는다. 등록 트랜잭션이 이미 롤백 표시라 트랜잭션 밖인 여기서 다시 부른다.
 * 다른 제약 위반은 그대로 던진다.
 */
@Slf4j
@Component
class CashflowCreator {
    private static final String REQUEST_KEY_CONSTRAINT = "uk_cashflow_item_request";

    private final CashflowService service;

    CashflowCreator(CashflowService service) {
        this.service = service;
    }

    /**
     * @param idempotencyKey 요청의 Idempotency-Key 헤더. null이면 키 없는 등록
     * @return 만들었거나(같은 키 재요청이면) 처음 만든 규칙
     */
    CashflowService.Item create(Long userId, CashflowService.CreateCommand command, String idempotencyKey) {
        if (idempotencyKey == null) {
            return service.create(userId, command, null);
        }
        String key = IdempotencyKeys.require(idempotencyKey);
        try {
            return service.create(userId, command, key);
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, REQUEST_KEY_CONSTRAINT)) {
                throw e;
            }
            log.info("같은 Idempotency-Key의 가계부 등록이 동시에 와 먼저 만든 항목으로 답한다 userId={}", userId);
            return service.create(userId, command, key);
        }
    }

    private static boolean violates(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
