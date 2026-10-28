package com.yamu.be.global.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 검증을 통과한 JWT를 {@link AuthUser} principal로 바꾼다. 컨트롤러는 {@code @AuthenticationPrincipal AuthUser}로 받는다.
 * 지금은 역할(ROLE)이 없어 권한 목록은 비어 있다.
 */
@Component
public class AuthUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            Long userId = Long.valueOf(jwt.getSubject());
            Number deviceId = jwt.getClaim(AccessTokenIssuer.DEVICE_CLAIM);
            if (deviceId == null) {
                throw new BadCredentialsException("기기 정보가 없는 토큰입니다.");
            }
            return new UsernamePasswordAuthenticationToken(new AuthUser(userId, deviceId.longValue()), jwt, List.of());
        } catch (NumberFormatException | ClassCastException e) {
            throw new BadCredentialsException("토큰의 사용자 정보 형식이 올바르지 않습니다.", e);
        }
    }
}
