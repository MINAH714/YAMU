package com.yamu.be.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계좌 연결 정책({@code yamu.account-link.*}). 기본값은 노션 명세 「계좌 확인」「1원 인증 요청」「계좌 연결」 기준이다.
 *
 * @param verificationTtl  계좌 확인 결과(verificationToken) 유효 시간(10분). 1원 인증을 요청하면 다시 10분으로 늘린다
 * @param authCodeValidity 1원 인증코드 유효 시간 안내(5분). SSAFY 문서에 없어 실측(5분 성공·10분 만료)으로 정했다. 최종 판단은 SSAFY A1087
 * @param resendInterval   같은 계좌로 1원을 다시 보낼 수 있는 간격(1분)
 * @param maxCodeAttempts  인증코드를 틀릴 수 있는 횟수(3회). SSAFY는 횟수를 제한하지 않아 YAMU가 센다. 넘으면 1원 인증부터 다시
 * @param authText         1원 입금자명 앞글자(SSAFY authText). 통장에 "YAMU 1234"로 찍힌다
 * @param idempotencyTtl   계좌 연결 Idempotency-Key 결과를 기억하는 시간
 * @param idempotencyLockTtl 같은 Idempotency-Key 요청을 처리 중이라고 표시해 두는 시간. 금융망 두 번(코드 확인·잔액) 제한 시간보다 길게
 * @param dailySendLimit   한 계좌로 한국 날짜 하루에 1원을 보낼 수 있는 횟수(5회). 재요청 간격만으로는 무한히 보낼 수 있어서 둔다
 * @param dailyFailLimit   한 계좌로 하루에 인증코드를 틀릴 수 있는 횟수(10회). 넘으면 그날은 그 계좌로 인증할 수 없다
 * @param recoveryDailySendLimit 기기 재등록에서 한 계좌로 하루에 1원을 보낼 수 있는 횟수(3회). 일반보다 낮게 둔다
 * @param exposeDemoCode   1원 응답에 인증 숫자(demoCode)를 내려줄지(임시). 시연 계좌는 사용자가 거래내역을 볼 수 없어 시연 서버는 켠다.
 *                         켜면 1원 인증이 계좌 주인 확인이 되지 않으므로 실제 계좌를 쓰게 되면 끈다
 */
@ConfigurationProperties(prefix = "yamu.account-link")
public record AccountLinkProperties(
        Duration verificationTtl,
        Duration authCodeValidity,
        Duration resendInterval,
        int maxCodeAttempts,
        String authText,
        Duration idempotencyTtl,
        Duration idempotencyLockTtl,
        int dailySendLimit,
        int dailyFailLimit,
        int recoveryDailySendLimit,
        boolean exposeDemoCode) {
}
