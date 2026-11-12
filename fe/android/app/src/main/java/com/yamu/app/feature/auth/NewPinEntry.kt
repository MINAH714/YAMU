package com.yamu.app.feature.auth

import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.PIN_SHAKE_MS
import com.yamu.app.feature.auth.data.AuthValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 6자리를 채운 뒤 점 6개를 보여 주는 시간 — 이 동안 누른 키는 버린다 */
internal const val PIN_FILLED_HOLD_MS = 250L

/** 새 간편 비밀번호를 두 번 넣는 칸 */
data class NewPinState(
    /** false = 첫 입력, true = 한 번 더 */
    val confirming: Boolean = false,
    val input: String = "",
    val error: String? = null,
    /** 틀릴 때마다 1씩 — 점이 흔들리고 진동이 한 번 온다(PinDots shakeKey) */
    val errorKey: Int = 0,
    val shuffleKey: Int = 0,
    /** 키를 받지 않는 중 — 6자리를 채운 직후 · 틀려서 흔들리는 동안 · 부른 쪽이 저장하는 동안 */
    val holding: Boolean = false,
)

/**
 * 새 간편 비밀번호 두 번 넣기 — A-06(가입 · 이 폰 등록)이 쓴다(QA 07 · P1-17).
 * - 6자리가 차면 점 6개를 [PIN_FILLED_HOLD_MS] 보여 준 뒤 확인 단계로. 키패드 배열은 그대로 둔다
 * - 쉬운 번호거나 처음과 다르면 첫 입력부터 다시 — 흔들리는 [PIN_SHAKE_MS] 동안 키를 버리고 키패드는 새로 섞는다
 * - 두 번 같으면 [onConfirmed]. 키는 계속 막아 두고, 저장이 실패하면 부른 쪽이 [fail]로 첫 입력부터 다시 하게 한다
 * 빠르게 누른 7번째 탭이 다음 입력의 첫 자리로 들어가지 않게 하는 게 목적이다
 */
internal class NewPinEntry(
    private val scope: CoroutineScope,
    private val onConfirmed: (pin: String) -> Unit,
) {
    private val _state = MutableStateFlow(NewPinState())
    val state: StateFlow<NewPinState> = _state.asStateFlow()
    private var first = ""
    private var pending: Job? = null

    fun onKey(key: KeypadKey) {
        val s = _state.value
        if (s.holding) return
        val next = s.input.applyKey(key)
        _state.update { it.copy(input = next, error = null) }
        if (next.length == 6) onFilled(next)
    }

    private fun onFilled(pin: String) {
        if (!_state.value.confirming) {
            AuthValidation.pin(pin)?.let { return fail(it) }
            first = pin
            _state.update { it.copy(holding = true) }
            pending = scope.launch {
                delay(PIN_FILLED_HOLD_MS)
                _state.update { it.copy(confirming = true, input = "", holding = false) }
            }
            return
        }
        if (pin != first) return fail("처음 입력과 달라요. 처음부터 다시 만들어 주세요")
        _state.update { it.copy(holding = true) }
        onConfirmed(pin)
    }

    /** 첫 입력부터 다시 — 흔들고, 흔들리는 동안 키를 버리고, 키패드는 새로 섞는다 */
    fun fail(message: String) {
        pending?.cancel()
        first = ""
        _state.update {
            NewPinState(error = message, errorKey = it.errorKey + 1, shuffleKey = it.shuffleKey + 1, holding = true)
        }
        pending = scope.launch {
            delay(PIN_SHAKE_MS.toLong())
            _state.update { it.copy(holding = false) }
        }
    }

    /**
     * 뒤로 — 확인 단계(또는 넘어가는 중)면 첫 입력으로 돌리고 true. 첫 입력이면 false(부른 쪽이 화면을 닫는다).
     * 두 번 넣어 저장을 기다리는 중이면 부른 쪽이 막는다
     */
    fun back(): Boolean {
        val s = _state.value
        val inConfirm = s.confirming || (s.holding && first.isNotEmpty())
        if (!inConfirm) return false
        pending?.cancel()
        first = ""
        _state.update { NewPinState(errorKey = it.errorKey, shuffleKey = it.shuffleKey) }
        return true
    }
}
