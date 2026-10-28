package com.yamu.be.global.crypto;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개인정보 암호화·조회 해시·PIN 해시에 쓰는 키 설정({@code yamu.crypto.*}). 값은 모두 Base64다.
 *
 * <p>키는 DB·깃·로그에 넣지 않는다. 운영은 환경변수로 넣고, 로컬·테스트는 각 프로필 설정에 전용 키를 둔다.
 * 키를 잃으면 암호화된 값은 다시 열 수 없고, hmacKey·pinPepper를 바꾸면 기존 해시가 전부 맞지 않는다.
 * 키 길이 검증은 {@link CryptoKeys}가 서버 시작 시 한다.
 *
 * @param dataKeyId 새로 암호화할 때 쓸 데이터 키 번호(1~255). 봉투에 기록되어 복호화 때 키를 고른다.
 * @param dataKeys  키 번호별 AES-256 키(32바이트). 키를 교체해도 옛 번호는 남겨 두어야 옛 데이터를 연다.
 * @param hmacKey   휴대폰·계좌·카드 조회 해시용 HMAC 키(32바이트 이상)
 * @param pinPepper PIN 해시에 섞는 서버 비밀값(32바이트 이상)
 */
@ConfigurationProperties(prefix = "yamu.crypto")
public record CryptoProperties(
        int dataKeyId,
        Map<Integer, String> dataKeys,
        String hmacKey,
        String pinPepper) {
}
