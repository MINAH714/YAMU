package com.yamu.app.data

import android.content.Context
import com.yamu.app.core.session.DevSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 사용자 — 가짜 은행(FakeBank)에서 무엇을 연결 · 등록했는지, 월급, 그 설정.
 * 바뀔 때마다 기기에 저장해 앱을 다시 켜도 같은 사용자로 보인다(S15P31E103-453). 저장한 값이 없으면 시드 사용자(카드 6장).
 * 계좌 · 카드 자체(은행 · 상품 · 끝자리 · 잔액)는 FakeBank 목록에 있고, 여기에는 그 assetId만 둔다.
 * 다른 가짜 저장소(홈 · 실적 · 결제 · 잔고예보 · 소비분석 …)는 FakeBank를 거쳐 같은 값을 읽는다(S15P31E103-454).
 */
@Singleton
class FakeUser internal constructor(private val storage: Storage?) {

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsStorage(context))

    /**
     * [seeded]가 true면 시드 사용자(가입한 지 오래돼 지난 결제 · 실적 기록이 있음), false면 방금 가입한 새 사용자.
     * [accounts] · [cards]는 연결한 계좌 · 등록한 카드 assetId(넣은 순서). [excluded]는 자동 선택에서 뺀 카드(INACTIVE).
     * [salary]는 B-06 월급 시트에서 넣은 월급 — 지금 월급은 [salaryOn]으로 읽는다(없으면 잔고예보는 BE처럼 30일)
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
        val salary: Salary? = null,
    ) {
        /**
         * 지금 월급 — B-06에서 넣은 값. 넣은 적 없는 시드 사용자는 늘 [today] + 25일이다.
         * 시드 월급은 저장하지 않아 며칠 뒤에 켜도 잔고예보 시연 숫자가 그대로고, 월급 칸이 없던 -453 저장값에도 붙는다(!294 리뷰)
         */
        fun salaryOn(today: LocalDate): Salary? = salary ?: seedSalary(today).takeIf { seeded }
    }

    /** 매월 [day]일(그달에 없으면 말일) [amount]원이 [account](assetId)로 들어온다 */
    @Serializable
    data class Salary(val day: Int, val amount: Long, val account: String) {
        /** [after] 다음 날부터 가장 가까운 월급날 — 오늘이 월급날이면 다음 달(잔고예보는 '월급 전날까지'라서) */
        fun nextAfter(after: LocalDate): LocalDate {
            val ym = YearMonth.from(after)
            val thisMonth = ym.atDay(minOf(day, ym.lengthOfMonth()))
            if (thisMonth.isAfter(after)) return thisMonth
            val next = ym.plusMonths(1)
            return next.atDay(minOf(day, next.lengthOfMonth()))
        }
    }

    /** 저장소 — 기기(SharedPreferences) 또는 테스트에서는 없음(메모리만) */
    interface Storage {
        fun load(): String?
        fun save(json: String)
    }

    @Volatile var state: State = storage?.load()?.let(::decode) ?: seed()
        private set

    init {
        publish()
    }

    @Synchronized
    fun update(change: (State) -> State) {
        state = change(state)
        storage?.save(JSON.encodeToString(State.serializer(), state))
        publish()
    }

    /** 디버그 메뉴 '가짜 사용자' 칸 — main의 디버그 메뉴는 debug 클래스를 모르므로 DevSession에 글로 넘긴다 */
    private fun publish() {
        DevSession.fakeUser = FakeBank.describe(state)
    }

    private class PrefsStorage(context: Context) : Storage {
        private val prefs = context.getSharedPreferences("yamu_debug_fake_user", Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString(KEY, null)
        override fun save(json: String) = prefs.edit().putString(KEY, json).apply()
    }

    companion object {
        private const val KEY = "state"
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * 디버그 '카드 6장' · 저장한 값이 없을 때 — 계좌 둘(KB ·1234 기준 · 신한 ·5678), 새로 나온 트래블로그(C7) 빼고 카드 6장,
         * 월급은 저장하지 않고 읽을 때 [seedSalary]로 셈한다([State.salaryOn])
         */
        fun seed() = State(
            seeded = true,
            accounts = listOf("A1", "A2"),
            cards = listOf("C1", "C2", "C3", "C4", "C5", "C6"),
            primaryAccount = "A1",
            defaultCard = "C2",
        )

        /** 시드 사용자 월급 — 기준 계좌(KB ·1234)로 [today] + 25일(잔고예보 시연 숫자가 이 날짜에 맞춰져 있다) */
        fun seedSalary(today: LocalDate) = Salary(today.plusDays(25).dayOfMonth, 2_800_000, "A1")

        /** 방금 가입한 사용자 — 가입 때 1원으로 연결한 계좌만 있고 카드 0장 · 월급 없음(실서버에서 '나중에 할게요'를 고른 사용자) */
        fun newUser(accounts: List<String> = listOf("A1"), primary: String? = accounts.firstOrNull()) =
            State(seeded = false, accounts = accounts, primaryAccount = primary)

        /** 앱 시작 때(DebugStateProvider) 저장해 둔 사용자를 디버그 메뉴에 먼저 보인다 — Hilt보다 먼저라 따로 읽는다 */
        fun showSaved(context: Context) {
            FakeUser(PrefsStorage(context))
        }

        private fun decode(json: String): State? = runCatching { JSON.decodeFromString(State.serializer(), json) }.getOrNull()
    }
}
