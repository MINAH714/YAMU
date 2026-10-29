package com.yamu.be.auth;

import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 휴대폰 본인인증으로 확인한 사람. Redis(인증 요청 → 가입 진행 토큰)에 이 모양으로 옮겨 다니고, 회원가입 때 app_user에 저장한다.
 * 휴대폰 번호 원문은 들고 다니지 않고 암호문(phoneEnc)과 조회 해시(phoneHash)만 둔다.
 *
 * @param phoneEnc  CipherField.APP_USER_PHONE으로 암호화한 정규화 번호. app_user.phone_enc에 그대로 넣는다
 * @param phoneHash LookupHasher.phone() 값. 기존 사용자 찾기(UK phone_hash, birth_date)에 쓴다
 */
public record VerifiedIdentity(String name, LocalDate birthDate, byte[] phoneEnc, byte[] phoneHash) {

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final HexFormat HEX = HexFormat.of();

    /** Redis Hash 필드. 다른 필드와 같은 Hash에 넣는다. */
    Map<String, String> toFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("birthDate", birthDate.toString());
        fields.put("phoneEnc", B64.encodeToString(phoneEnc));
        fields.put("phoneHash", HEX.formatHex(phoneHash));
        return fields;
    }

    /** 기존 사용자와 같은 사람인지 볼 때 쓴다. 번호·생년월일이 같아도 이름이 다르면 다른 사람이다(명세: 이름·생년월일·번호). */
    boolean sameNameAs(String otherName) {
        return otherName != null && name.strip().equals(otherName.strip());
    }

    /** 로그·예외 메시지에 개인정보가 찍히지 않게 이름은 가운데를 가리고 생년월일·번호는 뺀다. */
    @Override
    public String toString() {
        return "VerifiedIdentity[name=" + maskName(name) + ", birthDate=***, phone=***]";
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 2))
                + (name.length() > 2 ? name.substring(name.length() - 1) : "");
    }

    /** 필드가 빠졌거나 깨졌으면 IllegalArgumentException. 받는 쪽은 만료와 같이 처리한다. */
    static VerifiedIdentity fromFields(Map<?, ?> fields) {
        String name = (String) fields.get("name");
        if (name == null) {
            throw new IllegalArgumentException("name 없음");
        }
        return new VerifiedIdentity(name, LocalDate.parse((String) fields.get("birthDate")),
                B64D.decode((String) fields.get("phoneEnc")), HEX.parseHex((String) fields.get("phoneHash")));
    }
}
