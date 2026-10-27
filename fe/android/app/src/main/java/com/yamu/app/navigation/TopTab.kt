package com.yamu.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.yamu.app.core.designsystem.component.BottomBarItem
import com.yamu.app.core.designsystem.icon.YamuIcons
import kotlin.reflect.KClass

/**
 * 하단 탭 5개. [graph] = 탭의 하위 그래프, [route] = 탭 첫 화면.
 * 아이콘은 프로토타입과 같은 Material Symbols Rounded(home · qr_code_2 · target · donut_small · person), 고른 탭은 채운 모양.
 */
enum class TopTab(
    val label: String,
    val graph: Any,
    val graphClass: KClass<*>,
    val route: Any,
    val routeClass: KClass<*>,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("홈", HomeGraph, HomeGraph::class, Home, Home::class, YamuIcons.Home, YamuIcons.HomeFilled),
    PAY("카드추천", PayGraph, PayGraph::class, Recommend, Recommend::class, YamuIcons.QrCode, YamuIcons.QrCode),
    PERF("실적관리", PerfGraph, PerfGraph::class, PerfOverview, PerfOverview::class, YamuIcons.Target, YamuIcons.Target),
    REPORT("소비분석", ReportGraph, ReportGraph::class, Report, Report::class, YamuIcons.DonutSmall, YamuIcons.DonutSmallFilled),
    MY("마이", MyGraph, MyGraph::class, My, My::class, YamuIcons.Person, YamuIcons.PersonFilled),
    ;

    companion object {
        val items: List<BottomBarItem> = entries.map { BottomBarItem(it.label, it.icon, it.selectedIcon) }
    }
}
