package com.yamu.app.feature.payment.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 결제 상세(P-08)와 결제 내역(S-05)이 같이 보는 앱 메모리.
 *
 * - 메모: BE 결제 상세 응답에는 memo가 없고 목록에만 있어서, 목록에서 받은 메모를 두었다가 상세에 보인다.
 *   홈 · 알림에서 바로 상세로 들어가면 아직 모를 수 있다(BE 상세에 memo가 생기면 그 값을 먼저 쓴다).
 * - [version]: 취소 · 메모로 결제가 바뀔 때마다 오른다. 결제 내역은 돌아올 때 이 값이 바뀌었으면 첫 쪽부터 다시 받는다
 *   (첫 쪽만 새로 받으면 둘째 쪽 뒤에서 취소한 결제가 승인으로 남는다).
 */
@Singleton
class PaymentEdits @Inject constructor() {
    private val memos = ConcurrentHashMap<Long, String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun memo(paymentId: Long): String? = memos[paymentId]

    fun rememberMemo(paymentId: Long, memo: String?) {
        if (memo.isNullOrEmpty()) memos.remove(paymentId) else memos[paymentId] = memo
    }

    fun changed() = _version.update { it + 1 }
}
