package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class QaIbkSeedSpecTest {
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    String input() throws Exception {
        String text=Files.readString(Path.of("seed/qa/personas-three-ibk.example.json"));
        for(int i=1;i<=3;i++) for(int j=1;j<=3;j++) text=text.replace("REPLACE_WITH_ACCOUNT_"+i+"_"+j,"10000000"+i+"00"+j);
        return text;
    }
    @Test void threeFictionalEligiblePeopleHaveFourCardsAndMatchingBanks() throws Exception {
        var spec=json.readValue(input(),QaSeedSpec.class);
        spec.validate(LocalDate.of(2026,11,16));
        assertThat(spec.personas()).extracting(QaSeedSpec.Persona::name).containsExactly("최인국","남윤주","박준수");
        assertThatThrownBy(() -> json.readValue(input().replace("\"003\"","\"088\""),QaSeedSpec.class)
                .validate(LocalDate.of(2026,11,16))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.readValue(input().replace("MILITARY_EXAM_COMPLETED","UNCONFIRMED"),QaSeedSpec.class)
                .validate(LocalDate.of(2026,11,16))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacySerializationDoesNotAddEligibilityAndInvalidateDatasetFingerprint() throws Exception {
        var spec=json.readValue(Files.readString(Path.of("seed/qa/personas.example.json")),QaSeedSpec.class);
        assertThat(json.writeValueAsString(spec)).doesNotContain("issuanceEligibility");
    }
}
