package com.yamu.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.yamu.app.core.designsystem.component.BottomBarItem
import com.yamu.app.core.designsystem.icon.YamuTabIcons
import kotlin.reflect.KClass

/**
 * 하단 탭 넷. [graph] = 탭의 하위 그래프, [route] = 탭 첫 화면.
 * 막대 가운데의 '결제'는 탭이 아니라 결제 코드를 여는 버튼이라 여기 없다(YamuApp이 막대에 따로 넘긴다, S15P31E103-478).
 * 아이콘은 직접 그린 둥근 선(YamuTabIcons), 고른 탭은 채운 모양. 마이는 늘 채운 수달 얼굴이고 색만 바뀐다.
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
    HOME("홈", HomeGraph, HomeGraph::class, Home, Home::class, YamuTabIcons.Home, YamuTabIcons.HomeOn),
    REPORT("소비분석", ReportGraph, ReportGraph::class, Report, Report::class, YamuTabIcons.Report, YamuTabIcons.ReportOn),
    PERF("실적관리", PerfGraph, PerfGraph::class, PerfOverview, PerfOverview::class, YamuTabIcons.Perf, YamuTabIcons.PerfOn),
    MY("마이", MyGraph, MyGraph::class, My, My::class, YamuTabIcons.Otter, YamuTabIcons.Otter),
    ;

    companion object {
        val items: List<BottomBarItem> = entries.map { BottomBarItem(it.label, it.icon, it.selectedIcon) }
    }
}
