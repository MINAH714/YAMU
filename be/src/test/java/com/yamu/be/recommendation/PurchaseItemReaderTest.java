package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yamu.be.recommendation.RecommendationRows.row;
import com.yamu.be.recommendation.domain.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PurchaseItemReaderTest {
    @Test void inactiveCodeIsRejectedForNewRequestButPreservedTransactionsCanBeReplayed() {
        var repository=mock(PurchaseItemRepository.class);var reader=new PurchaseItemReader(repository);
        reader.validateNewSelection(null);verifyNoInteractions(repository);
        when(repository.findById("YOUTUBE_PREMIUM_SUBSCRIPTION")).thenReturn(Optional.of(row(PurchaseItem.class,"purchaseItemCode","YOUTUBE_PREMIUM_SUBSCRIPTION","active",false)));
        assertThatThrownBy(()->reader.validateNewSelection("YOUTUBE_PREMIUM_SUBSCRIPTION")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->reader.validateStored("YOUTUBE_PREMIUM_SUBSCRIPTION")).doesNotThrowAnyException();
        assertThatThrownBy(()->reader.validateStored("OTHER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reader.validateNewSelection("invalid-code")).isInstanceOf(IllegalArgumentException.class);
    }
}
