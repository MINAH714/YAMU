package com.yamu.app.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.LinkScenario
import com.yamu.app.core.session.PayScenario
import com.yamu.app.core.session.StartState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * debug 전용 — 디버그 메뉴 값(DevSession)을 기기에 저장해 앱을 다시 켜도 남게 한다(S15P31E103-453). release에는 들어가지 않는다.
 * '이 상태로 처음부터'(applyOnNextStart)는 같은 실행 안에서 한 번 쓰고 마는 값이라 저장하지 않는다.
 */
internal object DevSessionStore {
    private const val PREFS = "yamu_debug_session"

    private data class Values(
        val start: StartState,
        val home: HomeScenario,
        val link: LinkScenario,
        val pay: PayScenario,
        val fault: FaultScenario,
        val pastBalances: Boolean,
    )

    /** 저장한 값을 DevSession에 넣고, 그 뒤로 바뀔 때마다 저장한다. 이름이 바뀌어 못 읽는 값은 기본값 그대로 */
    fun attach(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.enumOf<StartState>("start")?.let { DevSession.startState = it }
        prefs.enumOf<HomeScenario>("home")?.let { DevSession.homeScenario = it }
        prefs.enumOf<LinkScenario>("link")?.let { DevSession.linkScenario = it }
        prefs.enumOf<PayScenario>("pay")?.let { DevSession.payScenario = it }
        prefs.enumOf<FaultScenario>("fault")?.let { DevSession.faultScenario = it }
        DevSession.pastBalances = prefs.getBoolean("pastBalances", false)
        // 디버그 메뉴 · 가짜 저장소가 바꾸면 Compose 스냅숏 알림으로 받는다(같은 값이면 다시 쓰지 않음).
        // 첫 값도 쓴다 — 모으기 시작하기 전에 바뀐 값이 있어도 빠지지 않게(읽은 값 그대로면 같은 값을 한 번 더 쓸 뿐)
        MainScope().launch {
            snapshotFlow { Values(DevSession.startState, DevSession.homeScenario, DevSession.linkScenario, DevSession.payScenario, DevSession.faultScenario, DevSession.pastBalances) }
                .collect { v ->
                    prefs.edit()
                        .putString("start", v.start.name)
                        .putString("home", v.home.name)
                        .putString("link", v.link.name)
                        .putString("pay", v.pay.name)
                        .putString("fault", v.fault.name)
                        .putBoolean("pastBalances", v.pastBalances)
                        .apply()
                }
        }
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enumOf(key: String): E? =
        getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
}

/**
 * debug 매니페스트에만 있는 빈 ContentProvider — 앱 프로세스가 뜰 때(Application.onCreate 전) 한 번 불려
 * 첫 화면이 그려지기 전에 디버그 메뉴 값을 읽어 둔다. main의 Application을 고치지 않으려고 이렇게 둔다.
 */
class DebugStateProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let {
            DevSessionStore.attach(it)
            FakeUser.showSaved(it)   // 디버그 메뉴 '가짜 사용자' 칸 — 가짜 저장소가 아직 안 만들어졌어도 보이게
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
