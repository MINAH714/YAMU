package com.yamu.be.merchant;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MerchantCatalogService {
    private final MerchantCatalogRepository merchants;
    public MerchantCatalogService(MerchantCatalogRepository merchants) { this.merchants = merchants; }
    public record MerchantItem(long merchantId, String brandCode, String merchantName, String merchantCategoryCode) { }
    /** 시뮬레이션 입력 경계에서 검증한 브랜드/업종. 채널·시설·물품 사실은 포함하지 않는다. */
    public record Selection(Long merchantId, String merchantCategoryCode) { }

    public List<MerchantCategories.Category> categories() { return MerchantCategories.list(); }
    public List<MerchantItem> merchants(String merchantCategoryCode) {
        MerchantCategories.requireSupported(merchantCategoryCode);
        return merchants.findByCategory(merchantCategoryCode).stream().map(m -> new MerchantItem(m.merchantId(),
                m.brandCode(), m.merchantName(), m.merchantCategoryCode())).toList();
    }

    /** 브랜드 미선택은 업종만 유지. 선택 시 DB 업종을 사용하며 요청의 모순은 거부한다. */
    public Selection resolveSelection(Long merchantId, String merchantCategoryCode) {
        if (merchantId == null) {
            MerchantCategories.requireSupported(merchantCategoryCode);
            return new Selection(null, merchantCategoryCode);
        }
        if (merchantId <= 0) throw MerchantCategories.invalid("merchantId", "올바른 브랜드를 선택해 주세요.");
        var merchant = merchants.findById(merchantId)
                .orElseThrow(() -> MerchantCategories.invalid("merchantId", "존재하지 않는 브랜드입니다."));
        MerchantCategories.requireSupported(merchant.merchantCategoryCode());
        if (merchantCategoryCode != null && !merchant.merchantCategoryCode().equals(merchantCategoryCode)) {
            throw MerchantCategories.invalid("merchantCategoryCode", "선택한 브랜드의 업종과 일치하지 않습니다.");
        }
        return new Selection(merchant.merchantId(), merchant.merchantCategoryCode());
    }
}
