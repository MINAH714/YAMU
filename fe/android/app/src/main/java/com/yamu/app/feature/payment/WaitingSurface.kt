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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
import com.yamu.app.core.designsystem.theme.screenSwapMs
import com.yamu.app.core.ui.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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
 * [entering]이면 처음 그려질 때 원판이 0.92 → 1로 커지고, 판 페이드(200ms)가 끝날 무렵 링이 한 바퀴(220ms) 그려지며
 * 헤일로가 숨을 시작한다(M-WAIT-ENTER). 회전 등으로 다시 그려질 때는 false로 넘겨 끝 상태로 바로 보인다.
 * [lightStatusBar]면 판이 화면을 덮은 동안 상태 표시줄 아이콘을 밝게 하고, false가 되거나 판이 사라지면 원래대로 돌린다.
 * [blockTouches]는 판이 걷히기 시작할 때 false로 넘겨 아래 화면을 바로 쓸 수 있게 한다. 이때는 판에 터치를 받는 것(스크롤 포함)을
 * 하나도 두지 않는다. Compose는 터치를 받는 것이 있는 맨 위 형제에서 찾기를 멈춰서, 하나라도 남으면 아래 화면에 닿지 않는다.
 * TalkBack에는 [title]이 판 제목(pane title)으로 읽히고 제목이 바뀌면 다시 읽힌다.
 *
 * 판 자체의 페이드와 뒤로가기는 부르는 화면 몫이다. 판이 떠 있는 동안 아래 화면은 터치만 막힐 뿐 TalkBack에는 그대로 보이므로,
 * 부르는 화면이 아래 내용을 접근성에서 숨겨야 한다.
 */
@Composable
fun WaitingSurface(
    phase: WaitPhase,
    title: String,
    modifier: Modifier = Modifier,
    pill: String? = null,
    entering: Boolean = false,
    lightStatusBar: Boolean = true,
    blockTouches: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val density = LocalDensity.current
    var bottomHeight by remember(bottom != null) { mutableStateOf(0.dp) }
    val scroll = rememberScrollState()
    StatusBarIcons(light = lightStatusBar, delayFirst = entering)
    Box(
        modifier
            .fillMaxSize()
            .background(c.primary)
            .semantics { paneTitle = title }
            .then(
                if (blockTouches) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val discTop = (maxHeight * DISC_CENTER - DiscSize / 2).coerceAtLeast(0.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (blockTouches) Modifier.verticalScroll(scroll) else Modifier)
                    .padding(
                        start = YamuSpacing.screen,
                        end = YamuSpacing.screen,
                        top = discTop,
                        bottom = bottomHeight + YamuSpacing.x6,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HaloDisc(phase, entering)
                Spacer(Modifier.height(TitleGap))
                Text(
                    title,
                    style = t.titleL,
                    color = c.onPrimary,
                    textAlign = TextAlign.Center,
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
 * 헤일로는 원판과 다른 층이라 원판만 커지는 등장에 같이 커지지 않는다.
 * [entering]이면 원판 0.92 → 1(400ms), 판 페이드가 끝나는 200ms에 링 그리기(220ms)와 헤일로 숨을 시작한다.
 * 모션 줄이기에서는 원판 크기 변화 없이, 링은 꽉 찬 채로, 헤일로는 멈춘 원으로 바로 보인다.
 */
@Composable
private fun HaloDisc(phase: WaitPhase, entering: Boolean) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    // 화면 기록(back stack entry)이 아니라 Activity를 본다. 화면 교체 페이드 동안 나가는 화면 기록은 STARTED 아래로 내려가
    // 헤일로가 따로 멈출 수 있어서다. 앱이 백그라운드로 갈 때만 멈춘다
    val lifecycle = LocalContext.current.findActivity()?.lifecycle ?: LocalLifecycleOwner.current.lifecycle
    val unknown = phase == WaitPhase.Unknown
    val animateIn = entering && !reduce
    val scale = remember { Animatable(if (reduce) 1f else YamuMotion.HALO_MIN) }
    val alpha = remember { Animatable(0f) }
    val discScale = remember { Animatable(if (animateIn) YamuMotion.CHARACTER_ENTER_FROM else 1f) }
    val discAlpha = remember { Animatable(if (animateIn) 0f else 1f) }
    val ring = remember { Animatable(if (animateIn) 0f else 1f) }
    // 처음 한 번만 판 페이드가 끝날 때까지 기다린다. 결과 불명에서 돌아올 때는 바로
    val haloWait = remember { mutableStateOf(animateIn) }
    LaunchedEffect(Unit) {
        if (!animateIn) return@LaunchedEffect
        val enter = tween<Float>(YamuMotion.CHARACTER_ENTER_MS, easing = YamuMotion.standard)
        launch { discScale.animateTo(1f, enter) }
        launch { discAlpha.animateTo(1f, enter) }
        ring.animateTo(1f, tween(YamuMotion.RING_MS, delayMillis = YamuMotion.SCREEN_SWAP_MS, easing = YamuMotion.inOut))
    }
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
        if (haloWait.value) {
            delay(scaledMs(YamuMotion.SCREEN_SWAP_MS))
            haloWait.value = false
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
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = discScale.value
                    scaleY = discScale.value
                    this.alpha = discAlpha.value
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.matchParentSize().background(c.surface, CircleShape))
            CharacterImage(YamuPose.Card, size = CharacterSize.L, circle = true)
            WaitRing(
                progress = { ring.value },
                color = if (unknown) c.warning else c.primary,
                modifier = Modifier.matchParentSize().padding(RingInset),
                dashed = unknown,
            )
        }
    }
}

/**
 * 대기 판 아래 버튼 두 개(결과 불명 — 결과 다시 확인 · 닫기). 판이 브랜드색이라 주 버튼은 흰 바탕에 브랜드색 글자,
 * 보조는 흰 글자로 둔다(앱 공용 버튼은 흰 화면용이라 판 위에서는 대비가 맞지 않는다)
 */
@Composable
fun ColumnScope.WaitPanelActions(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    val c = YamuTheme.colors
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().height(YamuSize.button),
        shape = RoundedCornerShape(YamuRadius.button),
        colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.primary),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(primary, style = YamuTheme.typography.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    TextButton(onClick = onSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).height(YamuSize.touch)) {
        Text(secondary, style = YamuTheme.typography.button, color = c.onPrimary)
    }
}

/**
 * 판이 덮은 동안 상태 표시줄 아이콘을 밝게. 판이 옅을 때 바꾸면 흰 바탕 위에 흰 아이콘이 되므로 [delayFirst]면 판 페이드가
 * 끝날 때까지 기다린다. [light]가 false가 되거나 판이 사라지면 처음 값으로 바로 되돌린다.
 */
@Composable
private fun StatusBarIcons(light: Boolean, delayFirst: Boolean) {
    val view = LocalView.current
    val window = LocalContext.current.findActivity()?.window ?: return
    val reduce = YamuTheme.reduceMotion
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val original = remember(controller) { controller.isAppearanceLightStatusBars }
    val wait = remember { mutableStateOf(delayFirst) }
    LaunchedEffect(controller, light) {
        if (!light) {
            controller.isAppearanceLightStatusBars = original
            wait.value = true   // 걷히던 판이 다시 들어오면 또 덮을 때까지 기다린다
            return@LaunchedEffect
        }
        if (wait.value) {
            delay(scaledMs(screenSwapMs(reduce)))
            wait.value = false
        }
        controller.isAppearanceLightStatusBars = false   // 밝은 아이콘
    }
    DisposableEffect(controller) {
        onDispose { controller.isAppearanceLightStatusBars = original }
    }
}

/** 애니메이션 배율(개발자 옵션 · 녹화용 5배)을 따라 기다린다. delay는 배율을 모르므로 직접 곱한다 */
private suspend fun scaledMs(ms: Int): Long = (ms * (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)).toLong()
