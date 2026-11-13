package com.yamu.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 사용자 — 가짜 은행(FakeBank)에서 무엇을 연결 · 등록했는지와 그 설정.
 * 바뀔 때마다 기기에 저장해 앱을 다시 켜도 같은 사용자로 보인다(S15P31E103-453). 저장한 값이 없으면 시드 사용자(카드 6장).
 * 계좌 · 카드 자체(은행 · 상품 · 끝자리)는 FakeBank 목록에 있고, 여기에는 그 assetId만 둔다.
 */
@Singleton
class FakeUser internal constructor(private val storage: Storage?) {

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsStorage(context))

    /**
     * [seeded]가 true면 시드 사용자(가입한 지 오래돼 지난 결제 · 실적 기록이 있음), false면 방금 가입한 새 사용자.
     * [accounts] · [cards]는 연결한 계좌 · 등록한 카드 assetId(넣은 순서). [excluded]는 자동 선택에서 뺀 카드(INACTIVE)
     */
    @Serializable
    data class State(
        val seeded: Boolean,
        val accounts: List<String>,
        val cards: List<String> = emptyList(),
        val primaryAccount: String? = null,
        val nicknames: Map<String, String> = emptyMap(),
        val accountAliases: Map<String, String> = emptyMap(),
        val excluded: Set<String> = emptySet(),
        val defaultCard: String? = null,
        val order: List<String> = emptyList(),
    )

    /** 저장소 — 기기(SharedPreferences) 또는 테스트에서는 없음(메모리만) */
    interface Storage {
        fun load(): String?
        fun save(json: String)
    }

    @Volatile var state: State = storage?.load()?.let(::decode) ?: SEED
        private set

    @Synchronized
    fun update(change: (State) -> State) {
        state = change(state)
        storage?.save(JSON.encodeToString(State.serializer(), state))
    }

    private class PrefsStorage(context: Context) : Storage {
        private val prefs = context.getSharedPreferences("yamu_debug_fake_user", Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString(KEY, null)
        override fun save(json: String) = prefs.edit().putString(KEY, json).apply()
    }

    companion object {
        private const val KEY = "state"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** 디버그 '카드 6장' · 저장한 값이 없을 때 — 계좌 둘(KB ·1234 기준 · 신한 ·5678), 새로 나온 트래블로그(C7) 빼고 카드 6장 */
        val SEED = State(
            seeded = true,
            accounts = listOf("A1", "A2"),
            cards = listOf("C1", "C2", "C3", "C4", "C5", "C6"),
            primaryAccount = "A1",
            defaultCard = "C2",
        )

        /** 방금 가입한 사용자 — 가입 때 1원으로 연결한 계좌만 있고 카드는 0장(실서버에서 '나중에 할게요'를 고른 사용자) */
        fun newUser(accounts: List<String> = listOf("A1"), primary: String? = accounts.firstOrNull()) =
            State(seeded = false, accounts = accounts, primaryAccount = primary)

        private fun decode(json: String): State? = runCatching { JSON.decodeFromString(State.serializer(), json) }.getOrNull()
    }
}
