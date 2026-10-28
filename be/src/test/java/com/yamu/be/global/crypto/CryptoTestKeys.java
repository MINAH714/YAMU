package com.yamu.be.global.crypto;

import java.util.Map;

/** 단위 테스트용 키. application-test.yml과 같은 값이다. */
final class CryptoTestKeys {

    static final String DATA_KEY_1 = "0EnDNJY8EiCQDK78eTZI238tmQcubOUaUQovAddqKuU=";
    static final String DATA_KEY_2 = "QtMBagkzR6RDQxrKVCjsIIbWYns3aRMksLkC+dwPfro=";
    static final String HMAC_KEY = "W6990e8xM9l51TNQdCULcgsTC3Z8cE1F9KAykAubMsY=";
    static final String PIN_PEPPER = "21zmotCRVO8RYY0N3gWiHctx+Sb9BSjCwaZZL+4K4Do=";

    private CryptoTestKeys() {
    }

    static CryptoProperties properties() {
        return new CryptoProperties(1, Map.of(1, DATA_KEY_1), HMAC_KEY, PIN_PEPPER);
    }

    static CryptoKeys keys() {
        return new CryptoKeys(properties());
    }
}
