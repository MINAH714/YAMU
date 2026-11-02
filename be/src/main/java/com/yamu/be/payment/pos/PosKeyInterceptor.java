package com.yamu.be.payment.pos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/pos/**} 전체의 {@code X-POS-Key}를 한 곳에서 확인한다({@link PosConfig}가 등록).
 *
 * <p>보안 설정은 POS API를 Bearer 검사 없이 열어 두므로(명세 1장: POS는 X-POS-Key 인증), 새 POS API를 추가하면서 서비스의 키 확인을
 * 빠뜨려도 여기서 막힌다. 요청 본문을 읽고 검증({@code @Valid})하기 전에 돌아 키가 없으면 400이 아니라 401이다.
 *
 * <p>한계(데모용 인증): 모의 POS 웹은 브라우저 앱이라 키가 빌드된 JS에 들어간다. 키는 하나이고 posCode와 묶여 있지 않으며 호출 횟수 제한도 없다.
 * 실제 돈이 움직이지 않는 시연용 인증으로 두었다.
 */
class PosKeyInterceptor implements HandlerInterceptor {

    private final PosTerminalResolver terminals;

    PosKeyInterceptor(PosTerminalResolver terminals) {
        this.terminals = terminals;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        terminals.authenticate(request.getHeader(PosTerminalResolver.HEADER));
        return true;
    }
}
