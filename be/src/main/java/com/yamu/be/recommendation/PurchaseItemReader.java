package com.yamu.be.recommendation;

import com.yamu.be.recommendation.domain.PurchaseItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 신규 POS 선택과 보존 거래 재현의 활성 상태 검증을 구분한다. */
@Component
@RequiredArgsConstructor
public class PurchaseItemReader {
    private final PurchaseItemRepository items;

    @Transactional(readOnly = true)
    public void validateNewSelection(String code) { validate(code, true); }

    @Transactional(readOnly = true)
    public void validateStored(String code) { validate(code, false); }

    private void validate(String code, boolean newSelection) {
        if (code == null) return;
        if (!code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("구매 항목 코드 형식 오류");
        var item = items.findById(code).orElseThrow(() -> new IllegalArgumentException("등록되지 않은 구매 항목"));
        if (newSelection && !item.isActive()) throw new IllegalArgumentException("비활성 구매 항목");
    }
}
