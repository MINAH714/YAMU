package com.yamu.be.payment.pos;

import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 모의 POS 설정({@code yamu.pos.*}). 명세 「POS 결제 요청 생성」 10/26 결정대로 POS 설정은 서버가 관리하고 새 테이블을 두지 않는다(임시: yml).
 * 시간 설정이 빠지면 첫 요청이 아니라 서버 기동 때 실패한다. 키는 로그에 남지 않게 {@link #toString()}에서 가린다.
 *
 * @param key                모의 POS 웹이 보내는 {@code X-POS-Key}. 운영은 환경변수, 로컬은 application-local.yml. 비어 있으면 POS를 쓸 수 없다
 * @param confirmWindow      POS 요청 뒤 사용자가 승인해야 하는 시간(60초, DEC-003). 응답의 confirmDeadline
 * @param scannedRetention   스캔된 바코드를 Redis에 더 두는 시간. 결제 마감 뒤에도 앱이 상태를 조회할 수 있게 마감보다 길게
 * @param snapshotTtl        추천 스냅샷 보관 시간(임시, 명세 「결제 상태·예외 처리」 Q20)
 * @param idempotencyTtl     Idempotency-Key 결과를 기억하는 시간(명세 1장: 24시간)
 * @param idempotencyLockTtl 같은 키를 처리 중이라고 표시해 두는 시간. 요청 처리(추천 계산 포함)보다 길게
 * @param terminals          posCode별 시나리오. 한 브랜드에 여러 시나리오(일반 매장·백화점 입점 등)를 둘 수 있다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.pos")
public record PosProperties(
        String key,
        @NotNull Duration confirmWindow,
        @NotNull Duration scannedRetention,
        @NotNull Duration snapshotTtl,
        @NotNull Duration idempotencyTtl,
        @NotNull Duration idempotencyLockTtl,
        @Valid List<Terminal> terminals) {

    public PosProperties {
        terminals = terminals == null ? List.of() : List.copyOf(terminals);
    }

    public Optional<Terminal> terminal(String posCode) {
        return terminals.stream().filter(t -> t.posCode().equals(posCode)).findFirst();
    }

    @Override
    public String toString() {
        return "PosProperties[key=" + (key == null || key.isBlank() ? "(없음)" : "****") + ", confirmWindow="
                + confirmWindow + ", scannedRetention=" + scannedRetention + ", snapshotTtl=" + snapshotTtl
                + ", idempotencyTtl=" + idempotencyTtl + ", idempotencyLockTtl=" + idempotencyLockTtl
                + ", terminals=" + terminals + "]";
    }

    /**
     * POS 시나리오 하나. 요청의 merchantId는 brandCode로 찾은 가맹점과 같아야 한다.
     *
     * @param channel      이 시나리오의 거래 채널
     * @param facilityType 입점 시설. 모르면 null(미확인)이며 GENERAL로 대체하지 않는다
     */
    public record Terminal(@NotBlank String posCode, @NotBlank String brandCode, @NotNull PaymentChannel channel,
            FacilityType facilityType) {}
}
