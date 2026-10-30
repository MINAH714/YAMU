package com.yamu.be.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 계좌 목록에서 보여 준 연결 후보를 Redis에 10분 보관한다. 앱에는 계좌번호 대신 이 번호(candidateId)만 준다.
 *
 * <pre>
 * candidateId: "cand_{12바이트 랜덤 Base64URL}"
 * account:cand:{candidateId} → JSON { userId, scope, bankCode, bankName, accountNoEnc, accountNoHash, last4, accountName }
 * </pre>
 *
 * <ul>
 *   <li>계좌번호를 앱에 주지 않으므로, 앱이 목록에 없던 계좌번호를 끼워 넣을 길이 없다</li>
 *   <li>후보는 만든 사용자와 쓰임새(scope)에 묶인다. 남의 후보, 기기 재등록용 후보로 연결하는 것 모두 "없음"과 같다</li>
 *   <li>계좌번호는 암호문으로만 둔다(Redis도 개인정보 저장소)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccountCandidateStore {

    private static final String KEY_PREFIX = "account:cand:";
    private static final String ID_PREFIX = "cand_";
    /** 형식이 다른 값은 Redis를 보지 않는다. ':' 같은 글자가 키에 섞이지 않게. */
    private static final Pattern ID_FORMAT = Pattern.compile("cand_[A-Za-z0-9_-]{16}");
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AccountLinkProperties properties;
    private final SecureRandom random = new SecureRandom();

    /** 후보마다 새 번호를 만들어 저장하고, 넘긴 순서대로 번호를 돌려준다. */
    public List<String> issue(List<AccountCandidate> candidates) {
        List<String> ids = new ArrayList<>(candidates.size());
        for (AccountCandidate candidate : candidates) {
            byte[] buf = new byte[12];
            random.nextBytes(buf);
            String id = ID_PREFIX + URL.encodeToString(buf);
            redis.opsForValue().set(KEY_PREFIX + id, write(candidate), properties.verificationTtl());
            ids.add(id);
        }
        return ids;
    }

    /** 이 사용자가 이 쓰임새로 받은 후보만 찾는다. 없거나(만료) 남의 것·다른 쓰임새면 empty. */
    public Optional<AccountCandidate> find(String candidateId, Long userId, CandidateScope scope) {
        if (candidateId == null || !ID_FORMAT.matcher(candidateId).matches()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(KEY_PREFIX + candidateId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            AccountCandidate candidate = objectMapper.readValue(json, AccountCandidate.class);
            return candidate.userId().equals(userId) && candidate.scope() == scope
                    ? Optional.of(candidate) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void delete(List<String> candidateIds) {
        redis.delete(candidateIds.stream().filter(id -> ID_FORMAT.matcher(id).matches()).map(id -> KEY_PREFIX + id).toList());
    }

    private String write(AccountCandidate candidate) {
        try {
            return objectMapper.writeValueAsString(candidate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 후보를 어디에 쓸 수 있는지. 목록을 부른 사람·상황에 따라 정해진다. */
    public enum CandidateScope {
        /** 가입 중: 고른 계좌 중 하나로 1원 인증 → 고른 계좌를 한꺼번에 연결 */
        SIGNUP,
        /** 가입 후 추가: 본인 인증만으로 연결(1원 없음) */
        MEMBER,
        /** 기기 재등록: 이미 연결된 계좌 하나로 1원 인증(연결하지 않음) */
        DEVICE_RECOVERY
    }

    /**
     * 계좌 목록 한 줄. 연결할 때 이 값을 그대로 쓴다.
     *
     * @param accountNoEnc  계좌번호 암호문(CipherField.LINKED_ACCOUNT_NO)
     * @param accountNoHash 조회 해시(은행코드 + 계좌번호). 이미 연결했는지·1원 시도 횟수 키에 쓴다
     * @param accountName   SSAFY 계좌 이름(예: "YAMU 입출금"). 화면 표시용
     */
    public record AccountCandidate(Long userId, CandidateScope scope, String bankCode, String bankName,
            byte[] accountNoEnc, byte[] accountNoHash, String last4, String accountName) {

        /** 1원 시도 횟수·하루 한도 키. 사용자 + 계좌 해시. */
        String accountKey() {
            return userId + ":" + HexFormat.of().formatHex(accountNoHash);
        }
    }
}
