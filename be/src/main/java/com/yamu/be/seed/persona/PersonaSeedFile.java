package com.yamu.be.seed.persona;

import com.yamu.be.card.domain.CardIssuerStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 가입 시연용 페르소나 파일({@code be/seed/persona.local.json})의 모양. 배열 하나에 여러 명을 넣는다.
 *
 * <p>계좌는 넣지 않는다. 계좌는 SSAFY에만 두고, 가입 중에 계좌 목록에서 골라 연결한다.
 * 이름·번호·생년월일 규칙은 본인인증 요청과 같다(같은 값으로 가입을 시연하므로).
 */
public final class PersonaSeedFile {

    private PersonaSeedFile() {
    }

    /**
     * @param label         로그에 쓸 이름표(예: "A 직장인"). 개인정보를 넣지 않는다. 없으면 순번만 쓴다
     * @param ssafyMemberId 실행하는 사람이 SSAFY에서 미리 만든 회원 ID
     * @param ssafyUserKey  그 회원의 userKey. 암호화해서 저장한다
     */
    public record Persona(
            @Size(max = 40, message = "label은 40자 이하입니다.")
            String label,
            @NotBlank(message = "name이 필요합니다.")
            @Size(min = 2, max = 20, message = "name은 2~20자입니다.")
            String name,
            @NotNull(message = "birthDate가 필요합니다.")
            @Past(message = "birthDate가 올바르지 않습니다.")
            LocalDate birthDate,
            @NotNull(message = "phone이 필요합니다.")
            @Pattern(regexp = "\\d{10,11}", message = "phone은 숫자 10~11자리입니다.")
            String phone,
            @NotNull(message = "ssafyMemberId가 필요합니다.")
            @Pattern(regexp = "[^\\s@]{1,64}@[^\\s@]{1,190}", message = "ssafyMemberId는 이메일 형식입니다.")
            String ssafyMemberId,
            @NotBlank(message = "ssafyUserKey가 필요합니다.")
            @Size(max = 200, message = "ssafyUserKey가 너무 깁니다.")
            String ssafyUserKey,
            @Size(max = 10, message = "카드는 10장까지입니다.")
            List<@NotNull(message = "카드 항목이 비었습니다.") @Valid Card> cards) {

        public Persona {
            // 본인인증 요청과 같게 앞뒤 공백을 먼저 뺀 뒤 길이를 본다.
            name = name == null ? null : name.strip();
            label = label == null || label.isBlank() ? null : label.strip();
            cards = cards == null ? List.of() : cards;
        }

        /** 개인정보 없이 로그에 쓸 이름. */
        String tag(int index) {
            return label == null ? "#" + index : "#" + index + "(" + label + ")";
        }

        @Override
        public String toString() {
            return "Persona[label=" + label + ", cards=" + cards.size() + "]";
        }
    }

    /**
     * @param productName 카드 상품 이름(card_product.product_name). 카드 카탈로그 시드가 먼저 들어가 있어야 한다
     * @param issuerCode  상품 이름이 여러 카드사에 겹칠 때만 넣는다
     * @param cardType    상품 이름이 신용·체크에 겹칠 때만 넣는다(CREDIT·CHECK)
     * @param expiry      유효기간(YYYY-MM). 저장할 칸이 없어 형식만 확인한다. 카드 확인 때 사용자가 다시 입력한다
     * @param issuerStatus 카드사 상태. 정지·만료 카드 시연용. 없으면 NORMAL
     */
    public record Card(
            @NotBlank(message = "productName이 필요합니다.")
            @Size(max = 150, message = "productName은 150자 이하입니다.")
            String productName,
            @Size(max = 32, message = "issuerCode는 32자 이하입니다.")
            String issuerCode,
            @Pattern(regexp = "CREDIT|CHECK", message = "cardType은 CREDIT·CHECK 중 하나입니다.")
            String cardType,
            @NotNull(message = "cardNo가 필요합니다.")
            @Pattern(regexp = "\\d{16}", message = "cardNo는 숫자 16자리입니다.")
            String cardNo,
            YearMonth expiry,
            CardIssuerStatus issuerStatus) {

        public Card {
            issuerStatus = issuerStatus == null ? CardIssuerStatus.NORMAL : issuerStatus;
        }

        @Override
        public String toString() {
            return "Card[productName=" + productName + ", last4=" + (cardNo == null || cardNo.length() < 4
                    ? "?" : cardNo.substring(cardNo.length() - 4)) + "]";
        }
    }
}
