package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.auth.PhoneVerificationStore.IssuedVerification;
import com.yamu.be.auth.SignupTokenStore.IssuedSignupToken;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.SignupStep;
import com.yamu.be.auth.dto.PhoneVerificationConfirmResponse;
import com.yamu.be.auth.dto.PhoneVerificationRequest;
import com.yamu.be.auth.dto.PhoneVerificationResponse;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.user.RejoinService;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 본인인증 확인에서 사용자를 찾은 뒤와 모드를 정하기 전 사이에 상태가 바뀌는 경우를 확인한다.
 * 통합 테스트로는 그 틈을 재현할 수 없어, 찾을 때와 잠그고 다시 읽을 때 다른 사용자를 돌려주게 한다.
 */
class SignupServiceTest {

    private static final LocalDate BIRTH = LocalDate.parse("1999-03-15");
    private static final byte[] PHONE_HASH = {1, 2, 3};
    private static final LocalDateTime EARLIER = LocalDateTime.parse("2026-10-01T00:00:00");

    private final PhoneVerificationStore verificationStore = mock(PhoneVerificationStore.class);
    private final SignupTokenStore tokenStore = mock(SignupTokenStore.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final SignupService service = new SignupService(verificationStore, tokenStore, userRepository,
            mock(RejoinService.class), mock(PersonalDataCipher.class), mock(LookupHasher.class),
            new SignupProperties(Duration.ofMinutes(3), Duration.ofMinutes(1), 5, Duration.ofMinutes(30), true, 30),
            mock(LoginRateLimiter.class), transaction,
            Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC));

    @Test
    @SuppressWarnings("unchecked")
    void 찾은_뒤_그사이_가입을_마쳤으면_기기_재등록으로_보내고_본인인증_시각을_덮어쓰지_않는다() {
        AppUser pendingWhenFound = seeded();
        AppUser activeWhenLocked = seeded();
        activeWhenLocked.markIdentityVerified(EARLIER);
        activeWhenLocked.changePin("pin-hash", EARLIER);
        activeWhenLocked.activate();

        when(verificationStore.confirm("pv_x", "123456"))
                .thenReturn(new VerifiedIdentity("김야무", BIRTH, new byte[] {9}, PHONE_HASH));
        when(userRepository.findByPhoneHashAndBirthDate(PHONE_HASH, BIRTH)).thenReturn(Optional.of(pendingWhenFound));
        when(userRepository.findByIdForUpdate(any())).thenReturn(Optional.of(activeWhenLocked));
        when(transaction.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
                .doInTransaction(null));
        when(tokenStore.issue(any())).thenReturn(new IssuedSignupToken("sgt_x", 1800));

        PhoneVerificationConfirmResponse response =
                service.confirmVerification("pv_x", "123456", new ClientInfo("127.0.0.1", "test"));

        assertThat(response.mode()).isEqualTo(SignupMode.DEVICE_RECOVERY);
        assertThat(response.nextStep()).isEqualTo(SignupStep.ACCOUNT_LINK);
        assertThat(activeWhenLocked.getIdentityVerifiedAt()).isEqualTo(EARLIER);
    }

    @Test
    void 인증번호를_응답으로_주지_않는_설정에서는_요청_단계에서_시드를_보지_않는다() {
        // 문자를 실제로 보내게 되면 인증번호를 받은 폰 주인만 결과를 알 수 있으므로 예전처럼 확인 단계에서만 대조한다.
        SignupService smsService = new SignupService(verificationStore, tokenStore, userRepository,
                mock(RejoinService.class), mock(PersonalDataCipher.class), mock(LookupHasher.class),
                new SignupProperties(Duration.ofMinutes(3), Duration.ofMinutes(1), 5, Duration.ofMinutes(30), false, 30),
                mock(LoginRateLimiter.class), transaction,
                Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC));
        when(verificationStore.issue(any())).thenReturn(new IssuedVerification("pv_x", "123456"));

        PhoneVerificationResponse response = smsService.requestVerification(
                new PhoneVerificationRequest("김야무", BIRTH, "SKT", "01012345678"),
                new ClientInfo("127.0.0.1", "test"));

        assertThat(response.verificationId()).isEqualTo("pv_x");
        assertThat(response.demoCode()).isNull();
        verifyNoInteractions(userRepository);
    }

    private static AppUser seeded() {
        AppUser user = AppUser.pending("김야무", BIRTH, new byte[] {9}, PHONE_HASH, null);
        user.linkFinMember("seed1@yamu.app", new byte[] {7});
        return user;
    }
}
