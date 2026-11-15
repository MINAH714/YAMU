package com.yamu.app.navigation

import android.os.SystemClock

/**
 * 앱을 열 때 시스템 스플래시를 언제 걷을지(S15P31E103-512).
 * 스플래시(Splash 경로)가 정한 첫 화면(A-01 · A-03)이 구성되면 [open]을 부른다. 그 전까지는 [keep]이 true라
 * 앱의 빈 상자가 그려지지 않고 시스템 스플래시가 남는다. 정하는 일이 늦거나 멈춰도 [maxMs]가 지나면 걷는다.
 * 화면을 다시 만들 때(회전 · 프로세스 복원)는 스플래시 경로를 지나지 않으므로 처음부터 [open]을 부른다
 */
internal class StartGate(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val maxMs: Long = MAX_KEEP_MS,
) {
    private val startedAt = now()

    @Volatile private var opened = false

    fun open() {
        opened = true
    }

    fun keep(): Boolean = !opened && now() - startedAt < maxMs

    companion object {
        /** 진짜 저장소는 세션 읽기 · 기기 키 확인이 수십 ms, debug 가짜 인증은 300ms를 일부러 기다린다. 그보다 넉넉하게 */
        const val MAX_KEEP_MS = 1_500L
    }
}
