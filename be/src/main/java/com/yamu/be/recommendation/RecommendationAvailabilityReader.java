package com.yamu.be.recommendation;

import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.domain.*;
import com.yamu.be.card.domain.*;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.CreditLimitSource;
import com.yamu.be.payment.billing.*;
import com.yamu.be.recommendation.RecommendationCommand.PaymentAvailability;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 추천 시점 참고 판정. 승인/출금/한도 차감은 호출하지 않는다. */
@Component
@Slf4j
class RecommendationAvailabilityReader {
    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final LinkedAccountRepository accounts;
    private final CardBillRepository bills;
    private final CreditLimitSource limits;
    private final AccountBalanceService balances;
    private final TransactionTemplate read;

    RecommendationAvailabilityReader(UserCardRepository cards, CardProductRepository products,
            LinkedAccountRepository accounts, CardBillRepository bills, CreditLimitSource limits,
            AccountBalanceService balances, PlatformTransactionManager transactions) {
        this.cards=cards; this.products=products; this.accounts=accounts; this.bills=bills;
        this.limits=limits; this.balances=balances;
        read=new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record Assessment(Map<Long, PaymentAvailability> cards, List<RecommendationDataIssue> issues) {
        Assessment { cards=Map.copyOf(cards); issues=List.copyOf(issues); }
    }
    private record Rows(List<UserCard> cards, Map<Long,CardProduct> products,
                        Map<Long,LinkedAccount> accounts) { }

    Assessment prepare(Long paymentId,long userId,long principal,Plan plan) {
        // 중단만 한 바깥 트랜잭션의 연결을 반환했다고 가정하지 않는다. 실제 호출부가 먼저 준비해야 한다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 가능 상태 조회는 DB 트랜잭션 시작 전에 준비해야 합니다.");
        }
        Rows rows;
        try {
            rows=Objects.requireNonNull(read.execute(status -> load(userId,plan)));
        } catch (TransientDataAccessException failure) {
            log.warn("recommendation_db_read_failed paymentId={} stage=AVAILABILITY_BATCH scope=UNKNOWN_CANDIDATES cardIds=[] code=DATABASE_READ_RETRYABLE exceptionType={} retryable=true",
                    paymentId,failure.getClass().getName());
            throw new RecommendationService.RecommendationReadException(paymentId);
        }
        var result=new LinkedHashMap<Long,PaymentAvailability>();
        var issues=new ArrayList<RecommendationDataIssue>();
        var byAccount=new LinkedHashMap<Long,List<Long>>();
        var creditIds=rows.cards().stream().filter(c -> c.getRegistrationStatus()==CardRegistrationStatus.ACTIVE
                && c.getIssuerStatus()==CardIssuerStatus.NORMAL && rows.products().containsKey(c.getCardProductId())
                && rows.products().get(c.getCardProductId()).getCardType()==com.yamu.be.card.domain.CardType.CREDIT)
                .map(UserCard::getId).toList();
        Set<Long> unpaid=Set.of();
        Set<Long> failedCreditRead=Set.of();
        if(!creditIds.isEmpty()) {
            try {
                unpaid=Objects.requireNonNull(read.execute(status -> bills.findByUserCardIdInAndStatus(creditIds,BillStatus.UNPAID).stream()
                        .filter(b->b.getUserId()==userId).map(CardBill::getUserCardId).collect(Collectors.toSet())));
            } catch(TransientDataAccessException failure) {
                failedCreditRead=Set.copyOf(creditIds);
                creditIds.forEach(id->issues.add(new RecommendationDataIssue(id,null,"DATABASE_READ_RETRYABLE",true)));
                log.warn("recommendation_db_read_failed paymentId={} stage=CREDIT_BILLS_READ scope=BATCH_POTENTIALLY_AFFECTED cardIds={} code=DATABASE_READ_RETRYABLE exceptionType={} retryable=true",
                        paymentId,creditIds,failure.getClass().getName());
            }
        }
        for(var card:rows.cards()) {
            String excluded=card.getRegistrationStatus()!=CardRegistrationStatus.ACTIVE ? "CARD_NOT_ACTIVE"
                    : card.getIssuerStatus()==CardIssuerStatus.EXPIRED ? "CARD_EXPIRED"
                    : card.getIssuerStatus()!=CardIssuerStatus.NORMAL ? "CARD_SUSPENDED" : null;
            if(excluded!=null) { result.put(card.getId(),unavailable(excluded)); continue; }
            var product=rows.products().get(card.getCardProductId());
            if(product==null) { result.put(card.getId(),unknown("PRODUCT_UNKNOWN")); continue; }
            if(plan==Plan.INSTALLMENT && product.getCardType()==com.yamu.be.card.domain.CardType.CHECK) {
                result.put(card.getId(),unavailable("CHECK_CARD_INSTALLMENT_NOT_SUPPORTED")); continue;
            }
            var account=card.getLinkedAccountId()==null ? null : rows.accounts().get(card.getLinkedAccountId());
            if(account==null) { result.put(card.getId(),unknown("PAYMENT_ACCOUNT_UNKNOWN")); continue; }
            if(!account.isConnected()) { result.put(card.getId(),unavailable("PAYMENT_ACCOUNT_DISCONNECTED")); continue; }
            if(product.getCardType()==com.yamu.be.card.domain.CardType.CHECK) {
                byAccount.computeIfAbsent(account.getId(),id->new ArrayList<>()).add(card.getId());
                continue;
            }
            if(failedCreditRead.contains(card.getId())) { result.put(card.getId(),unknown("DATABASE_READ_RETRYABLE")); continue; }
            if(unpaid.contains(card.getId())) { result.put(card.getId(),unavailable("UNPAID_BILL")); continue; }
            if(card.getPaymentDay()==null || !BillingCycle.supports(card.getPaymentDay(),product.getBillingCutoffDays())) {
                result.put(card.getId(),unknown("BILLING_CYCLE_UNSUPPORTED")); continue;
            }
            try {
                var check=limits.check(userId,card.getId(),principal);
                result.put(card.getId(),switch(check.result()) {
                    case EXCEEDED -> unavailable("CREDIT_LIMIT_EXCEEDED");
                    case UNKNOWN -> unknown("CREDIT_LIMIT_UNKNOWN");
                    case SUFFICIENT -> new PaymentAvailability(Availability.AVAILABLE,null);
                    case SKIPPED -> new PaymentAvailability(Availability.AVAILABLE,"CREDIT_LIMIT_CHECK_SKIPPED");
                });
                if(check.result()==CreditLimitSource.Result.SKIPPED) {
                    issues.add(new RecommendationDataIssue(card.getId(),null,"CREDIT_LIMIT_CHECK_SKIPPED",false));
                }
            } catch(TransientDataAccessException failure) {
                result.put(card.getId(),unknown("DATABASE_READ_RETRYABLE"));
                issues.add(new RecommendationDataIssue(card.getId(),null,"DATABASE_READ_RETRYABLE",true));
                log.warn("recommendation_db_read_failed paymentId={} stage=CREDIT_LIMIT_READ scope=CARD cardIds=[{}] code=DATABASE_READ_RETRYABLE exceptionType={} retryable=true",
                        paymentId,card.getId(),failure.getClass().getName());
            }
        }
        if(!byAccount.isEmpty()) {
            try {
                var partial=balances.getBalanceResults(userId,new ArrayList<>(byAccount.keySet()));
                byAccount.forEach((account,ids) -> {
                    var value=partial.get(account);
                    if(value!=null && value.balance()!=null) {
                        ids.forEach(id->result.put(id,value.balance().balance()>=principal
                                ? new PaymentAvailability(Availability.AVAILABLE,null) : unavailable("INSUFFICIENT_BALANCE")));
                    } else {
                        boolean retryable=value!=null && value.errorCode()!=null && Set.of(ErrorCode.UPSTREAM_TIMEOUT,ErrorCode.FIN_API_UNAVAILABLE,ErrorCode.FIN_API_ERROR).contains(value.errorCode());
                        markBalanceUnknown(paymentId,ids,result,issues,retryable ? "BALANCE_READ_RETRYABLE" : "BALANCE_ACCESS_UNKNOWN",retryable,null);
                    }
                });
            } catch(TransientDataAccessException failure) {
                markBalanceUnknown(paymentId,byAccount.values().stream().flatMap(Collection::stream).toList(),result,issues,
                        "DATABASE_READ_RETRYABLE",true,failure.getClass().getName());
            } catch(BusinessException failure) {
                markBalanceUnknown(paymentId,byAccount.values().stream().flatMap(Collection::stream).toList(),result,issues,
                        "BALANCE_ACCESS_UNKNOWN",false,failure.getClass().getName());
            }
        }
        result.forEach((id,state) -> {
            if(state.status()==Availability.UNKNOWN && issues.stream().noneMatch(i->Objects.equals(i.userCardId(),id)))
                issues.add(new RecommendationDataIssue(id,null,state.reason(),false));
        });
        return new Assessment(result,issues);
    }

    private Rows load(long userId,Plan plan) {
        var owned=cards.findOrdered(userId,List.of(CardRegistrationStatus.ACTIVE,CardRegistrationStatus.INACTIVE));
        if(owned.stream().anyMatch(c->c.getUserId()!=userId)) throw new IllegalArgumentException("다른 사용자의 카드는 조회할 수 없습니다.");
        var productIds=owned.stream().map(UserCard::getCardProductId).distinct().toList();
        var found=productIds.isEmpty() ? Map.<Long,CardProduct>of() : products.findAllById(productIds).stream().collect(Collectors.toMap(CardProduct::getId,Function.identity()));
        var accountIds=owned.stream().filter(c -> c.getRegistrationStatus()==CardRegistrationStatus.ACTIVE
                && c.getIssuerStatus()==CardIssuerStatus.NORMAL && found.containsKey(c.getCardProductId())
                && !(plan==Plan.INSTALLMENT && found.get(c.getCardProductId()).getCardType()==com.yamu.be.card.domain.CardType.CHECK))
                .map(UserCard::getLinkedAccountId).filter(Objects::nonNull).distinct().toList();
        var connected=accountIds.isEmpty() ? Map.<Long,LinkedAccount>of() : accounts.findByIdInAndUserId(accountIds,userId).stream()
                .filter(a->a.getUserId()==userId).collect(Collectors.toMap(LinkedAccount::getId,Function.identity()));
        return new Rows(owned,found,connected);
    }
    private static PaymentAvailability unavailable(String reason) { return new PaymentAvailability(Availability.UNAVAILABLE,reason); }
    private static PaymentAvailability unknown(String reason) { return new PaymentAvailability(Availability.UNKNOWN,reason); }
    private void markBalanceUnknown(Long paymentId,List<Long> ids,Map<Long,PaymentAvailability> result,List<RecommendationDataIssue> issues,
            String code,boolean retryable,String exceptionType) {
        ids.forEach(id->{ result.put(id,unknown(code)); issues.add(new RecommendationDataIssue(id,null,code,retryable)); });
        log.warn("recommendation_availability_read_failed paymentId={} stage=BALANCE_READ cardIds={} code={} exceptionType={} retryable={}",
                paymentId,ids,code,exceptionType,retryable);
    }
}
