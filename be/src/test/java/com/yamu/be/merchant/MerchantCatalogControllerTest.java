package com.yamu.be.merchant;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import com.yamu.be.global.security.AuthUserJwtConverter;
import com.yamu.be.global.security.JsonAuthenticationEntryPoint;
import com.yamu.be.global.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MerchantCatalogController.class)
@Import({MerchantCatalogService.class, SecurityConfig.class, AuthUserJwtConverter.class, JsonAuthenticationEntryPoint.class})
class MerchantCatalogControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MerchantCatalogRepository repository;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test void categoriesArePublicAndIncludeIndustriesWithoutBrands() throws Exception {
        mvc.perform(get("/api/merchant-categories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data[*].merchantCategoryCode").value(hasItems("COFFEE_SHOP", "OTHER", "DENTAL_CLINIC")))
                .andExpect(jsonPath("$.data[?(@.merchantCategoryCode == 'OTHER')].merchantCategoryName").value(contains("기타")));
        verifyNoInteractions(repository);
    }

    @Test void brandSelectionReturnsCalculationIdWithoutAuthentication() throws Exception {
        when(repository.findByCategory("OTHER")).thenReturn(List.of(
                new MerchantCatalogRepository.Merchant(9, "APP_STORE", "앱스토어", "OTHER")));
        mvc.perform(get("/api/merchants").param("merchantCategoryCode", "OTHER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].merchantId").value(9))
                .andExpect(jsonPath("$.data[0].brandCode").value("APP_STORE"))
                .andExpect(jsonPath("$.data[0].merchantName").value("앱스토어"))
                .andExpect(jsonPath("$.data[0].merchantCategoryCode").value("OTHER"));
        verify(repository).findByCategory("OTHER");
    }

    @Test void validEmptyIndustryIsAnEmptyArray() throws Exception {
        when(repository.findByCategory("DENTAL_CLINIC")).thenReturn(List.of());
        mvc.perform(get("/api/merchants").param("merchantCategoryCode", "DENTAL_CLINIC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", empty()));
    }

    @ParameterizedTest @ValueSource(strings = {"", "COFFEE", "coffee_shop", "COFFEE_SHOP ", "UNKNOWN", "TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE"})
    void invalidCodeIsNotNormalizedOrMappedToOther(String code) throws Exception {
        mvc.perform(get("/api/merchants").param("merchantCategoryCode", code))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("merchantCategoryCode"));
        verifyNoInteractions(repository);
    }

    @Test void missingFilterHasExistingFieldErrorShape() throws Exception {
        mvc.perform(get("/api/merchants"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("merchantCategoryCode"));
        verifyNoInteractions(repository);
    }

    @Test void otherPathsMethodsAndChildrenRemainProtected() throws Exception {
        for (var path : List.of("/api/payments/barcodes/123", "/api/merchants/1", "/api/merchant-categories/OTHER")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        mvc.perform(post("/api/merchants")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/merchant-categories")).andExpect(status().isUnauthorized());
    }
}
