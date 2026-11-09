package com.yamu.be.cashflow;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반복 지출 탐지 결과를 가계부 후보(CANDIDATE)에 맞춘다(S15P31E103-349). 탐지는 {@link RecurringExpenseDetector}가 하고,
 * 여기서는 한 트랜잭션 안에서 쓰기만 한다.
 *
 * <ul>
 *   <li>새 키: 후보 규칙과 첫 회차를 만든다. 같은 키가 이미 있으면(후보·확인·무시 모두) 만들지 않는다. 무시한 후보가 다시 생기지 않는다.</li>
 *   <li>이번에 안 잡힌 키: 확인 전 후보(CANDIDATE)이고 사용자가 손대지 않았으면 지운다(2026-11-09 결정). 손댄 후보는 사용자
 *       것이라 남긴다. 손댔는지는 규칙 행의 버전({@code @Version})과 회차 행으로 본다: 탐지는 규칙 행을 만들고 지우기만 하고
 *       고치지 않으므로, 버전이 0이 아니면 사용자가 값을 고쳤거나(제자리 수정·규칙 나누기) 상태를 바꾼 것이다(Codex 검토, 2026-11-09
 *       결정). 이번 회차만 고치거나 뺀 회차는 규칙 행을 바꾸지 않아 회차 행으로 본다. 확인·무시한 것은 그대로 둔다.</li>
 * </ul>
 */
@Component
class RecurringExpenseStore {
    private final CashflowItemRepository items;
    private final CashflowOccurrenceRepository occurrences;
    private final EntityManager entityManager;

    RecurringExpenseStore(CashflowItemRepository items, CashflowOccurrenceRepository occurrences,
            EntityManager entityManager) {
        this.items = items;
        this.occurrences = occurrences;
        this.entityManager = entityManager;
    }

    /**
     * 탐지 결과를 후보에 반영하고, 지금 잡힌 키의 확인 전 후보 규칙 번호를 돌려준다.
     *
     * @param userId   사용자
     * @param detected 이번에 잡힌 반복 지출(키마다 하나)
     * @return 규칙 번호 → 탐지 키. 지금 잡힌 키의 CANDIDATE 첫 규칙만 담는다
     */
    // 잠금을 기다린 뒤의 조회(이어지는 규칙·회차)가 앞 요청이 커밋한 값을 보도록 가계부 수정과 같이 READ COMMITTED로 연다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<Long, Key> apply(Long userId, List<Detected> detected) {
        Map<Key, CashflowItem> existing = new HashMap<>();
        // 키는 탐지 때 저장한 값이다. 사용자가 후보의 카드·금액을 고쳐도 같은 반복 지출로 알아본다(Codex 검토).
        for (CashflowItem item : items.findByUserIdAndDetectedUserCardIdIsNotNull(userId)) {
            existing.put(new Key(item.getDetectedUserCardId(), item.getDetectedMerchantId(), item.getDetectedAmount()),
                    item);
        }
        Set<Key> found = new HashSet<>();
        Map<Long, Key> candidates = new HashMap<>();
        for (Detected d : detected) {
            found.add(d.key());
            CashflowItem item = existing.get(d.key());
            if (item == null) {
                item = items.save(CashflowItem.detected(userId, d.key().userCardId(), d.key().merchantId(), d.title(),
                        d.key().amount(), d.firstDate(), d.dayOfMonth(), d.merchantCategoryCode()));
                occurrences.save(CashflowOccurrence.firstOf(item));
            }
            if (item.getStatus() == CashflowItem.Status.CANDIDATE) {
                candidates.put(item.getId(), d.key());
            }
        }
        existing.forEach((key, item) -> {
            if (!found.contains(key) && item.getStatus() == CashflowItem.Status.CANDIDATE) {
                removeIfUntouched(userId, item);
            }
        });
        return candidates;
    }

    /**
     * 행을 잠그며 DB 값으로 다시 읽고, 아직 손대지 않은 확인 전 후보면 회차와 함께 지운다. 그사이 다른 요청이 확인·무시했으면
     * (가계부 수정은 같은 첫 규칙 행을 잠근다) 그 커밋을 기다린 뒤 바뀐 상태를 보고 남긴다.
     */
    private void removeIfUntouched(Long userId, CashflowItem item) {
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (item.getStatus() != CashflowItem.Status.CANDIDATE || item.getVersion() != 0L) {
            return;
        }
        List<CashflowOccurrence> rows = occurrences.findByCashflowItemId(item.getId());
        if (rows.stream().anyMatch(row -> row.isOverridden() || row.getStatus() != CashflowOccurrence.Status.CONFIRMED)) {
            return;
        }
        occurrences.deleteAll(rows);
        items.delete(item);
    }

    /** 탐지 키. 같은 카드·가맹점·승인 금액이면 같은 반복 지출이다(생활비 반복 판정과 같은 키). */
    record Key(Long userCardId, Long merchantId, Long amount) {}

    /**
     * 이번에 잡힌 반복 지출 하나.
     *
     * @param firstDate  오늘 이후 첫 예정일(한국 날짜)
     * @param dayOfMonth 매월 기준일(마지막 결제일의 날)
     */
    record Detected(Key key, String title, String merchantCategoryCode, LocalDate firstDate, int dayOfMonth) {}
}
