package com.yamu.app.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 하단 탭 아이콘(S15P31E103-478) — 로고 글자처럼 끝을 둥글게 깎은 2dp 선으로 직접 그렸다(24 격자).
 * 고른 탭은 속을 채운 모양(`…On`), 마이는 늘 속까지 채운 수달 얼굴이다(안 고르면 회색, 고르면 메인 색 — 색은 [Icon]의 tint).
 * tint가 그림 전체 색을 바꾸므로 눈 · 코 · 문 같은 구멍은 바탕색으로 덮지 않고 EvenOdd로 비운다.
 * 시안: 11/13 'YAMU v4 화면 시안' 하단 탭.
 */
object YamuTabIcons {
    private const val HOUSE = "M4.6 10.4 11 5.3a1.6 1.6 0 0 1 2 0l6.4 5.1v8.3a1.8 1.8 0 0 1-1.8 1.8H6.4a1.8 1.8 0 0 1-1.8-1.8z"
    private const val WAVE = "M3.6 15.8c2.1 0 2.7-6.2 5.1-6.2s2.8 5.2 5.2 5.2 3.1-7.3 6.5-7.3"
    private const val BASELINE = "M3.6 20.2h16.8"
    private const val RING = "M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0z"
    private const val RING_ARC = "M12 4a8 8 0 1 1-7.6 10.5"

    val Home: ImageVector by lazy {
        icon("TabHome") {
            line(HOUSE)
            line("M9.9 20.5v-3.9a2.1 2.1 0 0 1 4.2 0v3.9")
        }
    }
    val HomeOn: ImageVector by lazy {
        icon("TabHomeOn") {
            fill("$HOUSE M10.2 20.4v-3.8a1.8 1.8 0 0 1 3.6 0v3.8z", evenOdd = true)   // 문은 구멍
            line(HOUSE)
        }
    }

    /** 소비분석 — 자산 흐름 그래프를 닮은 물결 */
    val Report: ImageVector by lazy {
        icon("TabReport") {
            line(WAVE)
            line(BASELINE)
        }
    }
    val ReportOn: ImageVector by lazy {
        icon("TabReportOn") {
            fill("$WAVE V20.2H3.6z", alpha = 0.3f)
            line(WAVE)
            line(BASELINE)
            fill("M18.5 7.5a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0z")
        }
    }

    /** 실적관리 — 채워 가는 링과 가운데 동전 */
    val Perf: ImageVector by lazy {
        icon("TabPerf") {
            line(RING, alpha = 0.32f)
            line(RING_ARC)
            fill("M9.9 12a2.1 2.1 0 1 0 4.2 0a2.1 2.1 0 1 0 -4.2 0z")
        }
    }
    val PerfOn: ImageVector by lazy {
        icon("TabPerfOn") {
            line(RING, alpha = 0.32f)
            line(RING_ARC, width = 2.8f)
            fill("M8.7 12a3.3 3.3 0 1 0 6.6 0a3.3 3.3 0 1 0 -6.6 0z")
        }
    }

    /** 마이 — 수달 얼굴. 고르든 안 고르든 같은 그림이고 색만 바뀐다 */
    val Otter: ImageVector by lazy {
        icon("TabOtter") {
            fill("M2.6 7.4a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z M16.2 7.4a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z")
            fill(
                "M12 4.6c5.1 0 9 3.5 9 8.1 0 4.7-3.9 8.1-9 8.1s-9-3.4-9-8.1c0-4.6 3.9-8.1 9-8.1z" +
                    " M7.55 12.1a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0z" +
                    " M13.75 12.1a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0z" +
                    " M10.1 15a1.9 1.3 0 1 0 3.8 0a1.9 1.3 0 1 0 -3.8 0z",
                evenOdd = true,   // 눈 둘 · 코는 구멍
            )
        }
    }

    /** 가운데 결제 버튼 — 네 귀퉁이 틀 안의 QR 조각 */
    val PayCode: ImageVector by lazy {
        icon("TabPayCode") {
            line("M4 8.2V5.6A1.6 1.6 0 0 1 5.6 4h2.6M15.8 4h2.6A1.6 1.6 0 0 1 20 5.6v2.6M20 15.8v2.6a1.6 1.6 0 0 1-1.6 1.6h-2.6M8.2 20H5.6A1.6 1.6 0 0 1 4 18.4v-2.6")
            fill(
                "M8.3 7.4h1.8a.9.9 0 0 1 .9.9v1.8a.9.9 0 0 1-.9.9H8.3a.9.9 0 0 1-.9-.9V8.3a.9.9 0 0 1 .9-.9z" +
                    " M13.9 7.4h1.8a.9.9 0 0 1 .9.9v1.8a.9.9 0 0 1-.9.9h-1.8a.9.9 0 0 1-.9-.9V8.3a.9.9 0 0 1 .9-.9z" +
                    " M8.3 13h1.8a.9.9 0 0 1 .9.9v1.8a.9.9 0 0 1-.9.9H8.3a.9.9 0 0 1-.9-.9v-1.8a.9.9 0 0 1 .9-.9z" +
                    " M13.4 13h.9a.4.4 0 0 1 .4.4v.9a.4.4 0 0 1-.4.4h-.9a.4.4 0 0 1-.4-.4v-.9a.4.4 0 0 1 .4-.4z" +
                    " M15.3 14.9h.9a.4.4 0 0 1 .4.4v.9a.4.4 0 0 1-.4.4h-.9a.4.4 0 0 1-.4-.4v-.9a.4.4 0 0 1 .4-.4z",
            )
        }
    }

    private class Scope(val builder: ImageVector.Builder) {
        fun line(d: String, width: Float = 2f, alpha: Float = 1f) {
            builder.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeAlpha = alpha,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(d: String, alpha: Float = 1f, evenOdd: Boolean = false) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = SolidColor(Color.Black),
                fillAlpha = alpha,
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
        }
    }

    private fun icon(name: String, draw: Scope.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .also { Scope(it).draw() }
            .build()
}
