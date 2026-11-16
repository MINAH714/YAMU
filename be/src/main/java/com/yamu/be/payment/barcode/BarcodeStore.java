package com.yamu.be.payment.barcode;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 바코드 보관소. 서버는 Redis({@link RedisBarcodeStore})를 쓴다. */
public interface BarcodeStore {

    /**
     * 새 바코드를 저장하고, 같은 사용자의 이전 바코드가 아직 ISSUED면 EXPIRED로 바꾼다.
     * 두 일은 한 번에 처리해 동시에 발급해도 ISSUED 바코드가 둘 생기지 않는다.
     *
     * @param retention 보관 시간. 지나면 바코드가 지워져 조회할 수 없다.
     * @return 같은 토큰이 이미 있으면 저장하지 않고 false
     */
    boolean issue(Barcode barcode, Duration retention);

    /** 형식이 다른 토큰은 Redis를 보지 않고 empty다. */
    Optional<Barcode> find(String token);

    /**
     * POS가 스캔해 결제 요청이 생겼다. ISSUED이고 만료 전일 때만 SCANNED로 바꾸고 결제 번호를 붙인다(한 번에 처리).
     * 같은 바코드를 동시에 스캔하면 한 건만 SCANNED를 받는다. 보관 시간은 결제 마감 이후까지 다시 늘린다.
     *
     * @param userId 바코드 주인. 부르는 쪽이 이미 읽은 값을 넘겨 바코드를 다시 읽지 않는다. 그 사용자의 현재 바코드가 이 토큰일 때만 보관 시간을 같이 늘린다
     * @param now    만료 판정 시각. 부르는 쪽의 시계와 같은 값을 쓴다
     */
    ScanResult scan(String token, Long userId, Long paymentId, Instant now, Duration retention);

    /**
     * 스캔은 됐는데 결제 요청 저장이 실패했을 때 되돌린다. 그 결제 번호로 SCANNED일 때만 ISSUED로 돌린다.
     *
     * @return 되돌렸으면 true
     */
    boolean revertScan(String token, Long paymentId);

    /**
     * 결제가 승인됐다. 그 결제 번호로 SCANNED인 바코드만 USED로 바꾼다(명세 「결제 상태·예외 처리」 4-1).
     * 토큰은 스캔 때 남긴 결제 번호 역색인으로 찾는다.
     *
     * @return 바꿨으면 true. 보관이 끝나 없거나 다른 상태·다른 결제면 false
     */
    boolean markUsed(Long paymentId);

    /** 결제가 확정 시간 안에 승인되지 않아 EXPIRED가 됐다. 그 결제 번호로 SCANNED인 바코드만 EXPIRED로 바꾼다. */
    boolean expireByPayment(Long paymentId);

    /**
     * POS 결제 취소(S15P31E103-424)에 쓴 바코드를 USED로 닫고 취소한 결제 번호를 붙인다. ISSUED일 때만 바꾼다.
     * 앱 바코드 조회는 붙은 결제를 같이 돌려주므로 앱은 {@code payment.status = CANCELED}로 취소 완료를 안다.
     * 그 사이 결제에 스캔됐으면(SCANNED) 그 결제를 막지 않게 그대로 둔다. 결제 번호 역색인은 만들지 않는다(승인·만료 반영 대상이 아니다).
     *
     * @return 바꿨으면 true. 없거나 다른 상태면 false
     */
    boolean useForCancel(String token, Long paymentId);

    /**
     * 다시 인증하지 않고 새 코드를 낸다(S15P31E103-530). 아래를 한 번에 확인하고 바꿔, 연타·스캔·새 발급이 동시에 와도 살아 있는 코드는 하나다.
     * <ul>
     *   <li>이전 코드가 그 사용자의 현재 코드이고, ISSUED 또는 결제에 쓰이지 않은 EXPIRED(시간 만료)이고, 결제 번호가 없다</li>
     *   <li>이전 코드는 EXPIRED로 닫고 {@code reissuedTo}에 새 토큰을 남긴다. 새 코드를 저장하고 사용자의 현재 코드로 둔다</li>
     *   <li>이전 코드가 이미 재발급됐고 그 새 코드가 아직 현재 코드 · ISSUED · 만료 전이면 저장하지 않고 그 토큰을 돌려준다</li>
     * </ul>
     * 사용자 · 기기 · 인증 시각처럼 발급 뒤 바뀌지 않는 값은 부르는 쪽이 먼저 확인한다.
     *
     * @param next 새 코드. 인증 정보(auth)는 이전 코드 것을 그대로 담아 온다
     * @param now  만료 판정 시각
     */
    Reissue reissue(String previousToken, Barcode next, Duration retention, Instant now);

    /** @param token REISSUED면 새로 저장한 토큰, ALREADY_REISSUED면 앞서 재발급한 토큰, 그 밖에는 null */
    record Reissue(ReissueResult result, String token) {
    }

    enum ReissueResult {
        REISSUED,
        /** 같은 이전 코드를 앞서 재발급했고 그 코드가 아직 쓸 수 있다 */
        ALREADY_REISSUED,
        /** 새 토큰이 이미 있다(난수 충돌). 다른 토큰으로 다시 부른다 */
        TOKEN_TAKEN,
        /** 현재 코드가 아님 · 스캔 · 결제 · 취소에 쓰임 · 보관 종료 등 재발급할 수 없다 */
        DENIED
    }

    enum ScanResult {
        SCANNED,
        /** 없는 토큰(보관 종료 포함) */
        NOT_FOUND,
        /** 발급 뒤 유효 시간이 지남 */
        EXPIRED,
        /** 이미 SCANNED 또는 USED */
        ALREADY_USED
    }
}
