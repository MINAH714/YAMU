package com.yamu.be.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * @param name        1원 인증 때 예금주와 비교한다
 * @param carrier     통신사. 연출이라 저장하지 않는다
 * @param phoneNumber 숫자만. 010 등 휴대폰 번호인지는 Service에서 정규화하며 확인한다
 */
public record PhoneVerificationRequest(
        @NotBlank(message = "이름이 필요합니다.")
        @Size(min = 2, max = 20, message = "이름은 2~20자입니다.")
        String name,
        @NotNull(message = "생년월일이 필요합니다.")
        @Past(message = "생년월일이 올바르지 않습니다.")
        LocalDate birthDate,
        @NotBlank(message = "통신사가 필요합니다.")
        @Pattern(regexp = "SKT|KT|LGU|MVNO", message = "통신사는 SKT·KT·LGU·MVNO 중 하나입니다.")
        String carrier,
        @NotBlank(message = "휴대폰 번호가 필요합니다.")
        @Pattern(regexp = "\\d{10,11}", message = "휴대폰 번호는 숫자 10~11자리입니다.")
        String phoneNumber) {

    /** 이름 앞뒤 공백은 검사 전에 뺀다. 뒤에서 빼면 " 김 "이 2자 검사를 통과한 뒤 한 글자 이름이 된다. */
    public PhoneVerificationRequest {
        name = name == null ? null : name.strip();
    }
}
