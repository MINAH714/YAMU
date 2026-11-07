package com.yamu.app.feature.payment

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.WaitRing
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.launch

/** 대기 판 상태. 처리 중은 실선 링 + 숨, 결과 불명은 주황 점선 + 헤일로 없음이라 정지 화면에서도 정상 대기와 갈린다 */
enum class WaitPhase { Processing, Unknown }

/** 흰 띠 원판: 캐릭터 128dp 둘레에 16dp 띠. 링은 띠 가운데(바깥에서 6dp 안) */
private val DiscSize = 160.dp
private val RingInset = 6.dp

/** 원판 가운데를 판 높이의 이 비율에 둔다. 글자 줄 수 · 아래 버튼과 상관없이 같은 자리 */
private const val DISC_CENTER = 0.4f

/**
 * 제목은 가장 크게 숨 쉰 헤일로 바깥에 둔다. 헤일로 위 흰 글자는 다크에서 대비가 약 2.5:1로 큰 글자 기준(3:1)에 못 미친다.
 * 원판 아래 끝에서 제목까지 = 헤일로 최대 반지름 − 원판 반지름 + 8dp
 */
private val TitleGap = DiscSize / 2 * (YamuMotion.HALO_DIAMETER_RATIO * YamuMotion.HALO_MAX) - DiscSize / 2 + 8.dp

/**
 * 결제 승인 대기 판(모션 명세 M-WAIT-HALO). 화면 전체를 브랜드색으로 덮고 원판 둘레에 링, 뒤에 숨 쉬는 헤일로를 둔다.
 * 판은 붙은 첫 프레임부터 아래 화면 터치를 막는다(투명도와 상관없음). 배경은 화면 끝까지, 내용은 시스템 바 안쪽에 둔다.
 * 판 위 흰 글자는 대비 때문에 큰 제목만 두고 카드 · 금액 · 안내 같은 작은 글자는 흰 알약([pill]) 안에 둔다.
 * [bottom](결과 다시 확인 같은 버튼)은 판 아래쪽에 겹쳐 놓아 나타나고 사라져도 원판이 움직이지 않는다.
 *
 * 들어오고 나가는 전환, 상태 표시줄 아이콘, 뒤로가기는 부르는 화면 몫이다. 판이 떠 있는 동안 아래 화면은 터치만 막힐 뿐
 * TalkBack에는 그대로 보이므로, 부르는 화면이 아래 내용을 접근성에서 숨겨야 한다. [ringProgress]는 링을 처음 그릴 때 쓴다.
 */
@Composable
fun WaitingSurface(
    phase: WaitPhase,
    title: String,
    modifier: Modifier = Modifier,
    pill: String? = null,
    ringProgress: () -> Float = { 1f },
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val density = LocalDensity.current
    var bottomHeight by remember(bottom != null) { mutableStateOf(0.dp) }
    Box(
        modifier
            .fillMaxSize()
            .background(c.primary)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val discTop = (maxHeight * DISC_CENTER - DiscSize / 2).coerceAtLeast(0.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = YamuSpacing.screen,
                        end = YamuSpacing.screen,
                        top = discTop,
                        bottom = bottomHeight + YamuSpacing.x6,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HaloDisc(phase, ringProgress)
                Spacer(Modifier.height(TitleGap))
                Text(
                    title,
                    style = t.titleL,
                    color = c.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Crossfade(
                    targetState = pill,
                    animationSpec = tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard),
                    label = "waitPill",
                ) { text ->
                    if (text != null) {
                        Text(
                            text,
                            style = t.bodyM,
                            color = c.text1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(top = YamuSpacing.x3)
                                .background(c.surface, RoundedCornerShape(percent = 50))
                                .padding(horizontal = YamuSpacing.x4, vertical = YamuSpacing.x2),
                        )
                    }
                }
            }
            if (bottom != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
                        .padding(horizontal = YamuSpacing.screen, vertical = YamuSpacing.x4),
                    content = bottom,
                )
            }
        }
    }
}

/**
 * 원판 + 링 + 헤일로. 원판은 움직이지 않고 헤일로만 0.96 ↔ 1.04로 숨 쉰다.
 * 앱이 백그라운드로 가면 숨을 멈추고 돌아오면 0.96부터 다시. 결과 불명이면 1.0으로 돌아가며 사라진다.
 * 모션 줄이기에서는 1.0에 멈춘 원 하나만 남긴다.
 * 헤일로는 원판과 다른 층이라, 원판만 키우는 등장을 붙여도 헤일로가 같이 커지지 않는다.
 */
@Composable
private fun HaloDisc(phase: WaitPhase, ringProgress: () -> Float) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unknown = phase == WaitPhase.Unknown
    val scale = remember { Animatable(if (reduce) 1f else YamuMotion.HALO_MIN) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(unknown, reduce) {
        val fade = tween<Float>(YamuMotion.SHORT_MS, easing = YamuMotion.standard)
        if (unknown) {
            // 멈춘 원을 남기면 정지 화면에서 숨 쉬는 헤일로와 구분되지 않아 아예 지운다
            launch { scale.animateTo(1f, fade) }
            alpha.animateTo(0f, fade)
            return@LaunchedEffect
        }
        if (reduce) {
            scale.snapTo(1f)
            alpha.animateTo(1f, fade)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            scale.snapTo(YamuMotion.HALO_MIN)
            launch { alpha.animateTo(1f, fade) }
            // 한 애니메이션으로 계속 오가야 반주기마다 프레임이 끊겨 주기가 길어지지 않는다
            scale.animateTo(
                YamuMotion.HALO_MAX,
                infiniteRepeatable(tween(YamuMotion.HALO_HALF_MS, easing = YamuMotion.inOut), RepeatMode.Reverse),
            )
        }
    }
    // 다크에서는 primaryContainer가 바탕보다 어두워 그림자처럼 보여서 흰색을 같은 투명도로 얹는다
    val halo = (if (c.isDark) c.onPrimary else c.primaryContainer).copy(alpha = YamuMotion.HALO_ALPHA)
    Box(Modifier.size(DiscSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val a = alpha.value
            if (a > 0f) {
                drawCircle(
                    color = halo.copy(alpha = halo.alpha * a),
                    radius = size.minDimension / 2 * YamuMotion.HALO_DIAMETER_RATIO * scale.value,
                )
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(c.surface, CircleShape))
            CharacterImage(YamuPose.Card, size = CharacterSize.L, circle = true)
            WaitRing(
                progress = ringProgress,
                color = if (unknown) c.warning else c.primary,
                modifier = Modifier.matchParentSize().padding(RingInset),
                dashed = unknown,
            )
        }
    }
}
