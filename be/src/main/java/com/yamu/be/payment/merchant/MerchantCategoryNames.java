package com.yamu.be.payment.merchant;

import com.yamu.be.merchant.MerchantCategories;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 업종 코드 → 표시 이름({@code GET /api/merchant-categories}와 같은 이름, Task 297 업종 목록). 결제 요청 조회·결제 목록·상세가
 * 같은 이름을 보이게 한 곳에 둔다.
 */
public final class MerchantCategoryNames {

    private static final Map<String, String> NAMES = MerchantCategories.list().stream()
            .collect(Collectors.toUnmodifiableMap(MerchantCategories.Category::merchantCategoryCode,
                    MerchantCategories.Category::merchantCategoryName));

    private MerchantCategoryNames() {
    }

    /** @return 표시 이름. 코드가 null이거나 업종 목록에 없으면 null */
    public static String of(String categoryCode) {
        return categoryCode == null ? null : NAMES.get(categoryCode);
    }
}
