package com.yamu.app.feature.payment.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 서버에서 확인한 메모 — [text]가 null이면 '메모 없음'을 확인한 것(모르는 것과 다르다) */
data class KnownMemo(val text: String?)

/**
 * 결제 상세(P-08)와 결제 내역(S-05)이 같이 보는 앱 메모리.
 *
 * - 메모: BE 결제 상세 응답에는 memo가 없고 목록에만 있어서, 목록에서 받은 메모를 두었다가 상세에 보인다.
 *   홈 · 알림 · 결제 완료에서 바로 상세로 들어왔거나 앱이 다시 켜졌으면 여기에 없다 — '모름'이다.
 *   모르는 채로 저장하면 서버의 메모를 확인 없이 덮어쓰므로, 상세는 모를 때 따로 찾아보고 그래도 모르면 저장 전에 알린다.
 * - [version]: 취소 · 메모로 결제가 바뀔 때마다 오른다. 결제 내역 · 결제 완료는 돌아올 때 이 값이 바뀌었으면 다시 받는다
 *   (첫 쪽만 새로 받으면 둘째 쪽 뒤에서 취소한 결제가 승인으로 남는다).
 * - 로그아웃 · 기기 해제 때 [clear] — 다음 사람에게 남의 메모가 보이지 않게.
 */
@Singleton
class PaymentEdits @Inject constructor() {
    private val memos = ConcurrentHashMap<Long, KnownMemo>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 확인한 적 없으면 null */
    fun memo(paymentId: Long): KnownMemo? = memos[paymentId]

    fun rememberMemo(paymentId: Long, memo: String?) {
        memos[paymentId] = KnownMemo(memo?.takeIf { it.isNotEmpty() })
    }

    fun changed() = _version.update { it + 1 }

    fun clear() {
        memos.clear()
        changed()
    }
}
