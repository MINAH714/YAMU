package com.yamu.be.global.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 검증을 통과한 JWT를 {@link AuthUser} principal로 바꾼다. 컨트롤러는 {@code @AuthenticationPrincipal AuthUser}로 받는다.
 * 지금은 역할(ROLE)이 없어 권한 목록은 비어 있다.
 *
 * <p>토큰의 기기가 해제됐으면 401 DEVICE_NOT_REGISTERED, 사용자가 탈퇴했거나 그 기기가 로그아웃했으면 401 UNAUTHORIZED로 막는다({@link ActiveSessionChecker}). 검사기는 DB가 없는
 * 웹 계층 테스트(@WebMvcTest)에서도 이 변환기가 뜨도록 있을 때만 쓴다.
 */
@Component
public class AuthUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ObjectProvider<ActiveSessionChecker> sessionChecker;

    public AuthUserJwtConverter(ObjectProvider<ActiveSessionChecker> sessionChecker) {
        this.sessionChecker = sessionChecker;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            Long userId = Long.valueOf(jwt.getSubject());
            Number deviceId = jwt.getClaim(AccessTokenIssuer.DEVICE_CLAIM);
            if (deviceId == null) {
                throw new BadCredentialsException("기기 정보가 없는 토큰입니다.");
            }
            ActiveSessionChecker checker = sessionChecker.getIfAvailable();
            ActiveSessionChecker.Status status = checker == null ? ActiveSessionChecker.Status.ACTIVE
                    : checker.status(userId, deviceId.longValue(), jwt.getIssuedAt());
            if (status == ActiveSessionChecker.Status.DEVICE_REVOKED) {
                throw new RevokedDeviceException();
            }
            if (status == ActiveSessionChecker.Status.USER_INACTIVE) {
                throw new BadCredentialsException("탈퇴했거나 활성이 아닌 사용자의 토큰입니다.");
            }
            if (status == ActiveSessionChecker.Status.LOGGED_OUT) {
                throw new BadCredentialsException("로그아웃한 기기의 토큰입니다.");
            }
            return new UsernamePasswordAuthenticationToken(new AuthUser(userId, deviceId.longValue()), jwt, List.of());
        } catch (NumberFormatException | ClassCastException e) {
            throw new BadCredentialsException("토큰의 사용자 정보 형식이 올바르지 않습니다.", e);
        }
    }
}
