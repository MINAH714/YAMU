package com.yamu.be.merchant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.recommendation.RecommendationInput;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MerchantSelectionTest {
    final MerchantCatalogRepository repository = mock(MerchantCatalogRepository.class);
    final MerchantCatalogService service = new MerchantCatalogService(repository);

    @Test void categoryOnlyAndBrandDeselectionKeepCategoryAndDoNotReadMerchant() {
        assertThat(service.resolveSelection(null, "COFFEE_SHOP"))
                .isEqualTo(new MerchantCatalogService.Selection(null, "COFFEE_SHOP"));
        verifyNoInteractions(repository);
    }

    @Test void brandUsesDatabaseIndustryAndCanBePassedToCalculatorTransaction() {
        when(repository.findById(9)).thenReturn(Optional.of(new MerchantCatalogRepository.Merchant(9, "APP_STORE", "앱스토어", "OTHER")));
        var selected = service.resolveSelection(9L, "OTHER");
        assertThat(service.resolveSelection(9L, null)).isEqualTo(selected);
        var tx = new RecommendationInput.Transaction(10000, RecommendationInput.Plan.LUMP_SUM, 1, Instant.EPOCH,
                selected.merchantId(), selected.merchantCategoryCode(), Map.of());
        assertThat(tx.merchantId()).isEqualTo(9L);
        assertThat(tx.merchantCategoryCode()).isEqualTo("OTHER");
        assertThat(tx.facts()).isEmpty();
        assertThat(tx.purchaseItemCode()).isNull();
        assertThat(tx.simulationRules()).isEmpty();
        assertThatThrownBy(() -> service.resolveSelection(9L, "COFFEE_SHOP"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test void missingOrInvalidBrandAndMissingCategoryAreRejected() {
        when(repository.findById(999)).thenReturn(Optional.empty());
        for (Long id : new Long[]{0L, -1L, 999L}) {
            assertThatThrownBy(() -> service.resolveSelection(id, "OTHER")).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.resolveSelection(null, null)).isInstanceOf(BusinessException.class);
        when(repository.findById(9)).thenReturn(Optional.of(new MerchantCatalogRepository.Merchant(9, "UNKNOWN", "미분류", "UNKNOWN")));
        assertThatThrownBy(() -> service.resolveSelection(9L, null)).isInstanceOf(BusinessException.class);
    }

    @Test void categoryCatalogHasUniqueCodesAndStableNameOrder() {
        var categories = service.categories();
        assertThat(categories).extracting(MerchantCategories.Category::merchantCategoryCode).doesNotHaveDuplicates();
        assertThat(categories).isSortedAccordingTo(Comparator.comparing(MerchantCategories.Category::merchantCategoryName)
                .thenComparing(MerchantCategories.Category::merchantCategoryCode));
    }

    @Test void allCategoryCodesInSeedDictionaryAreSupportedWithoutTransactionConditions() throws Exception {
        var rows = java.nio.file.Files.readAllLines(java.nio.file.Path.of("seed/catalog-ab20/codes/merchant-categories.csv"));
        var supported = service.categories().stream().map(MerchantCategories.Category::merchantCategoryCode).toList();
        for (var row : rows.subList(1, rows.size())) {
            var fields = row.split(",", -1);
            if (fields[1].equals("CATEGORY")) assertThat(supported).contains(fields[2].split("\\|"));
            else assertThat(supported).doesNotContain(fields[2]);
        }
    }
}
