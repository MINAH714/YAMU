package com.yamu.be.auth;

import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SSAFY 금융망 사용자 계정(member)을 만든다. 사용자는 이메일을 입력하지 않고, 서버가 ID를 만든다(API 명세 「회원가입」).
 *
 * <pre>
 * ID: "y" + 영문 소문자·숫자 9자리 랜덤 + "@yamu.app"   예) y8k2m4p9qx@yamu.app (19자, SSAFY 제한 40자)
 * </pre>
 *
 * <p>사용자 번호 대신 랜덤을 쓰는 이유: SSAFY에는 계정 삭제 API가 없어, 로컬 DB를 초기화해 번호가 1부터 다시 시작되면
 * 이미 만든 계정과 겹친다. 겹치면(E4002) 그 계정에 연결하지 않고 새 ID로 다시 만든다. 남의 userKey를 받아 쓰면 안 되기 때문이다.
 * 결과 불명(응답 시간 초과)이면 다시 보내지 않고 실패로 끝낸다. 대신 보낸 ID를 로그와 가입 진행 토큰에 남겨 두고,
 * 다음 시도에서 그 ID를 먼저 조회(/member/search)해 생겨 있으면 그 계정을 쓴다. 계정이 하나 버려지는 것을 막기 위해서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsafyMemberRegistrar {

    static final String MEMBER_PATH = "/member";
    static final String MEMBER_SEARCH_PATH = "/member/search";
    static final String DUPLICATE_ID = "E4002";
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String ID_DOMAIN = "@yamu.app";

    private final FinApiClient finApiClient;
    private final SignupProperties properties;
    private final SecureRandom random = new SecureRandom();

    /**
     * 금융망 실패는 FinApiException 그대로(502·504). ID가 계속 겹치면 마지막 E4002 실패를 던진다.
     *
     * @param pendingMemberId 지난 시도가 결과 불명으로 끝난 ID. 있으면 먼저 조회해 생겨 있으면 그 계정을 쓴다. 없으면 null
     * @param beforeSend      새 ID를 SSAFY에 보내기 직전에 부른다. 결과 불명에 대비해 ID를 적어 두는 데 쓴다
     */
    public RegisteredMember register(String pendingMemberId, Consumer<String> beforeSend) {
        if (pendingMemberId != null) {
            Optional<RegisteredMember> created = find(pendingMemberId);
            if (created.isPresent()) {
                log.info("지난 시도에서 결과 불명이던 SSAFY 계정 {}이 생겨 있어 그대로 씀", pendingMemberId);
                return created.get();
            }
        }
        FinApiException lastDuplicate = null;
        for (int attempt = 0; attempt < properties.memberIdAttempts(); attempt++) {
            String memberId = newMemberId();
            beforeSend.accept(memberId);
            try {
                MemberResponse member = finApiClient.postWithApiKey(MEMBER_PATH, Map.of("userId", memberId),
                        MemberResponse.class);
                return new RegisteredMember(memberId, member.userKey());
            } catch (FinApiException e) {
                if (e.getOutcome() == FinApiOutcome.UNKNOWN) {
                    // 계정이 생겼을 수 있다. ID는 서버가 만든 랜덤 값이라 개인정보가 아니어서 남겨 둔다.
                    log.warn("SSAFY 계정 생성 결과 불명: memberId={} (다음 시도에서 조회)", memberId);
                }
                if (e.getOutcome() != FinApiOutcome.REJECTED || !DUPLICATE_ID.equals(e.getFinCode())) {
                    throw e;
                }
                log.info("SSAFY 계정 ID 중복(E4002) → 새 ID로 다시 만듦 ({}번째)", attempt + 1);
                lastDuplicate = e;
            }
        }
        throw lastDuplicate;
    }

    /** 거절(없는 ID 등)이면 empty. 연결 실패·결과 불명은 그대로 던진다(지금 상태를 모르니 새로 만들지 않는다). */
    private Optional<RegisteredMember> find(String memberId) {
        try {
            MemberResponse member = finApiClient.postWithApiKey(MEMBER_SEARCH_PATH, Map.of("userId", memberId),
                    MemberResponse.class);
            return Optional.of(new RegisteredMember(memberId, member.userKey()));
        } catch (FinApiException e) {
            if (e.getOutcome() == FinApiOutcome.REJECTED) {
                return Optional.empty();
            }
            throw e;
        }
    }

    String newMemberId() {
        StringBuilder id = new StringBuilder("y");
        for (int i = 0; i < 9; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.append(ID_DOMAIN).toString();
    }

    /** SSAFY 회원 생성·조회 응답. userKey가 빠진 2xx는 FinApiClient가 결과 불명으로 끝낸다. */
    record MemberResponse(@NotBlank String userId, @NotBlank String userKey) {
    }

    /** @param userKey 평문. 받은 즉시 암호화해 저장하고 로그·응답에 남기지 않는다 */
    public record RegisteredMember(String memberId, String userKey) {

        @Override
        public String toString() {
            return "RegisteredMember[memberId=" + memberId + ", userKey=***]";
        }
    }
}
