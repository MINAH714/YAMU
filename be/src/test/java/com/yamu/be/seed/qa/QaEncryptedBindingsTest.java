package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.global.crypto.*;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import org.junit.jupiter.api.Test;

class QaEncryptedBindingsTest {
    @Test void bindsOnlyMatchingMemberAndBanksAndRejectsWrongAad() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var spec=mapper.readValue(Path.of("seed/qa/life-persona-1.example.json").toFile(),QaSeedSpec.class);
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        byte[] hmac=new byte[32],pepper=new byte[32];Arrays.fill(hmac,(byte)1);Arrays.fill(pepper,(byte)2);
        var cipher=new PersonalDataCipher(new CryptoKeys(new CryptoProperties(1,Map.of(1,key),
                Base64.getEncoder().encodeToString(hmac),Base64.getEncoder().encodeToString(pepper))),new SecureRandom());
        var root=mapper.createObjectNode();var person=root.putArray("personas").addObject();
        person.put("name",spec.personas().get(0).name());person.put("ssafyMemberId",spec.personas().get(0).ssafyMemberId());
        person.put("ssafyUserKeyEnc",Base64.getEncoder().encodeToString(cipher.encrypt("test-binding",CipherField.APP_USER_SSAFY_USER_KEY)));
        var accounts=person.putArray("accounts");
        for(var a:spec.personas().get(0).accounts()) {
            var node=accounts.addObject();node.put("bankCode",a.bankCode());node.put("last4",a.accountNo().substring(a.accountNo().length()-4));
            node.put("accountNoEnc",Base64.getEncoder().encodeToString(cipher.encrypt(a.accountNo(),CipherField.LINKED_ACCOUNT_NO)));
        }
        var bound=QaEncryptedBindings.bind(spec,root,cipher);
        assertThat(bound.personas().get(0).ssafyUserKey()).isEqualTo("test-binding");
        assertThat(bound.personas().get(0).accounts()).isEqualTo(spec.personas().get(0).accounts());
        person.put("ssafyUserKeyEnc",accounts.get(0).path("accountNoEnc").asText());
        assertThatThrownBy(()->QaEncryptedBindings.bind(spec,root,cipher)).isInstanceOf(IllegalStateException.class);
        person.put("name","another user");
        assertThatThrownBy(()->QaEncryptedBindings.bind(spec,root,cipher)).isInstanceOf(IllegalArgumentException.class);
    }
}
