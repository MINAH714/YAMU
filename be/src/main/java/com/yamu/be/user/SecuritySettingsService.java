package com.yamu.be.user;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.BarcodeAuthMethod;
import com.yamu.be.user.dto.SecuritySettingsResponse;
import com.yamu.be.user.dto.SecuritySettingsUpdateRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보안 설정 수정(노션 API 명세 V2 「보안 설정 수정」 No.26, FR-071·FR-072, S15P31E103-353). 현재 값은 내 정보 조회에 들어 있다.
 *
 * <p>임시로 정한 것(명세와 다름):
 * <ul>
 *   <li>명세는 본인 인증(X-Step-Up-Token, 가정)을 적었지만 앱이 보내지 않아 받지 않는다. 앱 안의 잠금 시간이라 위험이 작다</li>
 *   <li>{@code PIN_ONLY}는 저장만 한다. 결제 코드를 열 때 지문을 막는 것은 결제 코드 쪽에서 아직 읽지 않는다</li>
 *   <li>{@code setupGraceSeconds}·{@code barcodeAuthGraceSeconds}는 앱이 바꾸지 않아 받지 않고 명세 기본값을 돌려준다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SecuritySettingsService {

    static final Set<Integer> AUTO_LOCK_SECONDS = Set.of(0, 60, 300);
    static final int SETUP_GRACE_SECONDS = 300;
    static final int BARCODE_AUTH_GRACE_SECONDS = 60;

    private final AppUserRepository userRepository;

    /** 보낸 칸만 바꾼다. 허용 값 밖이면 400 INVALID_REQUEST(fieldErrors). 같은 사용자의 동시 수정은 사용자 행 잠금으로 차례로 처리한다. */
    @Transactional
    public SecuritySettingsResponse update(AuthUser me, SecuritySettingsUpdateRequest request) {
        Integer autoLock = request.autoLockSeconds();
        if (autoLock != null && !AUTO_LOCK_SECONDS.contains(autoLock)) {
            throw invalid("autoLockSeconds", "자동 잠금은 0(즉시)·60·300초 중에서 골라 주세요.");
        }
        BarcodeAuthMethod method = request.barcodeAuthMethod() == null ? null : method(request.barcodeAuthMethod());
        AppUser user = userRepository.findByIdForUpdate(me.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.changeSecuritySettings(autoLock, method);
        userRepository.saveAndFlush(user);
        return view(user);
    }

    static SecuritySettingsResponse view(AppUser user) {
        return new SecuritySettingsResponse(user.getAutoLockSeconds(), SETUP_GRACE_SECONDS, BARCODE_AUTH_GRACE_SECONDS,
                user.getBarcodeAuthMethod().name(), user.getUpdatedAt());
    }

    private static BarcodeAuthMethod method(String value) {
        return Arrays.stream(BarcodeAuthMethod.values()).filter(m -> m.name().equals(value)).findFirst()
                .orElseThrow(() -> invalid("barcodeAuthMethod", "결제 코드 인증 방식은 BIOMETRIC_OR_PIN 또는 PIN_ONLY입니다."));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }
}
