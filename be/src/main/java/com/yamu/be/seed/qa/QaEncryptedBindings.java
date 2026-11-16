package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import java.util.*;

/** Decrypts pre-existing dedicated accounts in memory. Never creates financial members/accounts. */
final class QaEncryptedBindings {
    private QaEncryptedBindings() {}
    static QaSeedSpec bind(QaSeedSpec input,JsonNode root,PersonalDataCipher cipher) {
        var people=new ArrayList<QaSeedSpec.Persona>();
        for(var p:input.personas()) {
            var matches=new ArrayList<JsonNode>();
            root.path("personas").forEach(row->{if(p.ssafyMemberId().equals(row.path("ssafyMemberId").asText()))matches.add(row);});
            QaSeedSpec.require(matches.size()==1,"Encrypted member missing or ambiguous");
            var row=matches.get(0);
            QaSeedSpec.require(p.name().equals(row.path("name").asText())&&row.path("accounts").size()==p.accounts().size(),"Encrypted identity mismatch");
            var accounts=new ArrayList<QaSeedSpec.Account>();
            for(var a:p.accounts()) {
                var found=new ArrayList<JsonNode>();
                row.path("accounts").forEach(bank->{if(a.bankCode().equals(bank.path("bankCode").asText()))found.add(bank);});
                QaSeedSpec.require(found.size()==1,"Encrypted account missing or ambiguous");
                var bank=found.get(0);
                String number=cipher.decrypt(Base64.getDecoder().decode(bank.path("accountNoEnc").asText()),CipherField.LINKED_ACCOUNT_NO);
                QaSeedSpec.require(number.endsWith(bank.path("last4").asText())&&bank.path("last4").asText().length()==4,"Encrypted account suffix mismatch");
                accounts.add(new QaSeedSpec.Account(a.bankCode(),number));
            }
            String userKey=cipher.decrypt(Base64.getDecoder().decode(row.path("ssafyUserKeyEnc").asText()),CipherField.APP_USER_SSAFY_USER_KEY);
            people.add(new QaSeedSpec.Persona(p.key(),p.scenario(),p.name(),p.birthDate(),p.phone(),p.ssafyMemberId(),userKey,accounts,p.cards(),p.issuanceEligibility()));
        }
        return new QaSeedSpec(input.datasetKey(),input.version(),input.baseDate(),List.copyOf(people));
    }
}
