package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.layout.width
import com.yamu.app.core.designsystem.icon.YamuIcons
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import android.os.SystemClock
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yamu.app.core.designsystem.theme.YamuMotion
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yamu.app.R
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.ui.isNotReadyMessage
import kotlin.math.max

/** '자세히 ⌄ / 접기 ⌃' — 화면에서 뺀 설명은 지우지 않고 여기로. 화면에 1개까지 */
@Composable
fun MoreDetails(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier) {
        ExpandToggle(if (expanded) "접기" else "자세히", expanded = expanded, onToggle = { expanded = !expanded })
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 2.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/**
 * 펼치기 글자 버튼 — '자세히' · '모두 보기' · '3장 더 보기'. 13sp Medium 회색 + expand_more 16dp, 높이 40dp.
 * 글자가 내용 왼쪽 끝과 맞도록 터치 영역만 왼쪽으로 8dp 넓힌다(프로토타입 details.more summary).
 */
@Composable
fun ExpandToggle(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = YamuTheme.colors
    Row(
        modifier
            .offset(x = (-8).dp)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "펼침" else "접힘" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, style = YamuTheme.typography.link, color = c.text3)
        Icon(if (expanded) YamuIcons.ExpandLess else YamuIcons.ExpandMore, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

/** 시작 준비 단계 표시 — 막대 [total]칸 + '1/2 · 연결 완료' */
@Composable
fun StartStepper(step: Int, total: Int, modifier: Modifier = Modifier, label: String? = null) {
    val c = YamuTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            // 이름이 있으면(가입) 위 4 · 아래 0 — 이름 아래는 화면 머리 위 여백이 맡는다. 막대만(연결)은 위 0 · 아래 8(프로토타입 .steps · .su)
            .padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = if (label != null) 4.dp else 0.dp, bottom = if (label != null) 0.dp else 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${total}단계 중 ${step}단계" + (label?.let { ", $it" } ?: "") },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) c.primary else c.outlineVariant),
                )
            }
        }
        if (label != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.primary)) { append("$step") }
                    append("/$total $label")
                },
                style = YamuTheme.typography.labelM,
                color = c.text3,
            )
        }
    }
}

/** 실적 게이지 한 줄 — 서버가 준 인정 실적 · 기준을 그대로 그린다(앱이 판정하지 않음) */
@Composable
fun PerformanceGauge(
    current: Long,
    target: Long,
    modifier: Modifier = Modifier,
    showCaption: Boolean = true,
    height: Dp = 6.dp,                  // 목록 6dp · 카드 상세 4dp(프로토타입)
    recent: Long? = null,               // 이번 결제로 늘어난 몫 — 연한 파랑(R-01d)
    ticks: List<Long> = emptyList(),    // 혜택 구간 눈금(30만 원 등, R-02)
) {
    val c = YamuTheme.colors
    fun ratioOf(v: Long) = if (target <= 0) 0f else (v.toFloat() / target).coerceIn(0f, 1f)
    val ratio = ratioOf(current)
    val achieved = target in 1..current
    val fill = if (achieved) c.success else c.primary
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height + if (ticks.isEmpty()) 0.dp else 6.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.outlineVariant)) {
                if (recent != null && recent > 0 && !achieved) {
                    Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(CircleShape).background(fill.copy(alpha = 0.45f)))
                    Box(Modifier.fillMaxWidth(ratioOf(current - recent)).fillMaxHeight().clip(CircleShape).background(fill))
                } else {
                    Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(CircleShape).background(fill))
                }
            }
            ticks.forEach { tick ->
                val r = ratioOf(tick)
                if (r in 0.01f..0.99f) {
                    Row(Modifier.fillMaxWidth()) {
                        if (r > 0f) Spacer(Modifier.weight(r))
                        Box(Modifier.width(2.dp).fillMaxHeight().background(c.text1))
                        Spacer(Modifier.weight(1f - r))
                    }
                }
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${current.toWon()} / ${target.toWon()}원 · " +
                    if (achieved) "달성" else "${(target - current).toWon()}원 남음",
                style = YamuTheme.typography.bodyS,
                color = c.text3,
            )
        }
    }
}

/**
 * 야무(수달) 11컷 — 업스케일한 캐릭터 시트에서 잘라 낸 컷(투명 배경, 무손실 WebP, 원본 크기). 시트 40컷 전체는 저장소 밖에 둔다.
 * 원형일 때는 채우기보다 [circleZoom]배 키우고 [circleBiasX] · [circleBiasY]로 옮겨 얼굴이 원 가운데 오게 한다.
 * 값은 컷마다 머리 위치를 재서 맞췄다(머리가 원 지름의 약 55%) — 꼬리 · 반짝이 때문에 머리가 그림 가운데에 있지 않다
 */
enum class YamuPose(@DrawableRes val res: Int, val circleZoom: Float, val circleBiasY: Float, val circleBiasX: Float = 0f) {
    Happy(R.drawable.yamu_full_happy, 1.21f, -1.61f, 0.22f),      // '시작/환영'
    Wave(R.drawable.yamu_full_wave, 1.08f, -1.47f, 0.61f),        // '인사 모션 3'
    Card(R.drawable.yamu_full_card, 1.23f, -1.69f, 0.65f),        // '메인 포즈(카드)'
    Coins(R.drawable.yamu_full_coins, 1.19f, -1.68f),             // '계좌/입금'
    Alert(R.drawable.yamu_full_alert, 1f, 0f),                    // '단호/주의' — 가슴까지만 있는 컷이라 원형 전용
    Done(R.drawable.yamu_full_done, 1.26f, -1.52f),               // '성과/달성'
    Chart(R.drawable.yamu_full_chart, 1.29f, -1.67f, -0.26f),     // '리포트/분석'
    Safe(R.drawable.yamu_full_safe, 1.22f, -1.68f, -0.17f),       // '보안/안전'
    Joy(R.drawable.yamu_full_joy_3, 1.17f, -1.5f, 0.11f),         // '기쁨 모션 3' — 가입 축하
    Pay(R.drawable.yamu_full_pay, 1.36f, -1.66f, -0.68f),         // '결제/사용' — 결제 완료
    ThumbsUp(R.drawable.yamu_full_thumbsup, 1.32f, -1.54f),       // '확신/좋아요' — 사도 괜찮아요
}

enum class CharacterSize(val size: Dp) { S(64.dp), M(96.dp), L(128.dp) }

/**
 * 캐릭터 — 장식이라 contentDescription 없음.
 * [circle]이면 연한 원 안에 얼굴 쪽만(카드 · 목록 안 작은 자리), 아니면 배경 없이 전신(환영 · 완료처럼 가운데 크게). 기본은 S만 원형.
 * 자리 크기는 둘 다 [size] 정사각형이라 둘레 배치는 그대로.
 *
 * 움직임은 둘이다.
 * - [animate]: 처음 그려질 때 조용한 등장(0.92 → 1 + 페이드, 넘침 없음). 결제 완료처럼 자주 보는 화면은 이것만 쓴다.
 * - [celebrateKey]: 카드 등록 · 가입 완료처럼 드문 순간의 축하 팝(눌림 → 정점 → 안착 + 고리 · 파티클).
 *   null이 아닌 새 값이 올 때마다 한 번 돈다. 키를 받자마자 [onCelebrated]가 불리니, 화면은 거기서 키를 비워
 *   단계를 되돌아오거나 화면이 다시 만들어져도 같은 축하가 또 돌지 않게 한다.
 *   [celebrateDelayMs]만큼 기다렸다가 시작한다(화면 교체 페이드가 끝난 뒤 돌게). 기다리는 동안은 멈춘 그림이다.
 *   [burstLayer]를 넘기면 고리 · 파티클을 그 층(화면 맨 위)에 그려 스크롤 · 상단 바가 자르지 않는다.
 *   팝 도중 화면이 멈추면(앱 백그라운드) 끝 상태로 바로 간다.
 * 모션 줄이기가 켜져 있으면 둘 다 크기 · 회전 · 고리 · 파티클 없이 짧은 페이드만 남는다.
 * 기다렸다 시작하는 팝은 화면 교체 페이드가 이미 있으니 캐릭터 페이드도 없이 멈춘 그림이다.
 */
@Composable
fun CharacterImage(
    pose: YamuPose,
    modifier: Modifier = Modifier,
    size: CharacterSize = CharacterSize.M,
    animate: Boolean = false,
    circle: Boolean = size == CharacterSize.S,
    celebrateKey: Any? = null,
    onCelebrated: () -> Unit = {},
    celebrateDelayMs: Int = 0,
    burstLayer: CelebrationLayerState? = null,
) = CharacterImageImpl(
    pose, modifier, size, animate, circle, celebrateKey, onCelebrated,
    tilt = true, celebrateDelayMs = celebrateDelayMs, burstLayer = burstLayer,
)

/** [tilt]는 컴포넌트 갤러리에서 팝 크기만 재려고 회전을 끌 때만 false */
@Composable
internal fun CharacterImageImpl(
    pose: YamuPose,
    modifier: Modifier,
    size: CharacterSize,
    animate: Boolean,
    circle: Boolean,
    celebrateKey: Any?,
    onCelebrated: () -> Unit,
    tilt: Boolean,
    celebrateDelayMs: Int = 0,
    burstLayer: CelebrationLayerState? = null,
) {
    val reduce = YamuTheme.reduceMotion
    val enter = animate && celebrateKey == null
    val scale = remember { Animatable(if (enter && !reduce) YamuMotion.CHARACTER_ENTER_FROM else 1f) }
    val alpha = remember { Animatable(if (enter) 0f else 1f) }
    val rotation = remember { Animatable(0f) }
    val burst = remember { Animatable(BURST_TOTAL_MS.toFloat()) }
    var burstParts by remember { mutableStateOf(BurstParts.NONE) }
    val streak = remember { BurstStreak() }
    // 팝은 키가 바뀌어도(화면이 onCelebrated에서 키를 비워도) 끊기지 않게 이 자리의 범위에서 돈다
    val scope = rememberCoroutineScope()
    val latestOnCelebrated by rememberUpdatedState(onCelebrated)
    val pop = remember { PopRun() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    /** 팝을 끊고 끝 상태(포스터 프레임)로 */
    suspend fun rest() {
        burstParts = BurstParts.NONE
        burst.snapTo(BURST_TOTAL_MS.toFloat())
        scale.snapTo(1f)
        rotation.snapTo(0f)
        alpha.snapTo(1f)
    }

    // 팝 도중 화면이 멈추면(앱 백그라운드 · 다른 화면으로 감) 돌아왔을 때 이어 돌지 않게 끝 상태로
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pop.job?.isActive == true) {
                pop.job?.cancel()
                scope.launch { rest() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!enter) return@LaunchedEffect
        if (reduce) {
            alpha.animateTo(1f, tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard))
        } else {
            val spec = tween<Float>(YamuMotion.CHARACTER_ENTER_MS, easing = YamuMotion.standard)
            launch { alpha.animateTo(1f, spec) }
            scale.animateTo(1f, spec)
        }
    }

    LaunchedEffect(celebrateKey) {
        if (celebrateKey == null) return@LaunchedEffect
        latestOnCelebrated()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect   // 안 보이는 동안 온 축하는 건너뜀
        pop.job?.cancel()
        pop.job = scope.launch {
            if (reduce) {
                rest()
                if (celebrateDelayMs > 0) return@launch   // 화면 교체 페이드만으로 들어온다
                alpha.snapTo(0f)
                alpha.animateTo(1f, tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard))
                return@launch
            }
            if (celebrateDelayMs > 0) {
                rest()   // 앞 팝을 끊고 왔으면 기다리는 동안 반쯤 돈 모습이 남지 않게
                // 화면 교체 페이드처럼 시스템 애니메이션 배율을 따라 기다린다(delay만 쓰면 배율을 바꾼 기기에서 페이드와 어긋남)
                delay((celebrateDelayMs * (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)).toLong())
            }
            burstParts = streak.next(SystemClock.uptimeMillis())
            alpha.snapTo(1f)
            scale.snapTo(1f)
            rotation.snapTo(0f)
            launch { scale.animateTo(1f, popScale()) }
            if (tilt) launch { rotation.animateTo(0f, popRotation()) }
            burst.snapTo(0f)
            burst.animateTo(BURST_TOTAL_MS.toFloat(), tween(BURST_TOTAL_MS, easing = LinearEasing))
            burstParts = BurstParts.NONE   // 다 그렸으면 Canvas를 뺀다. 새 팝이 끊으면 여기까지 오지 않는다
        }
    }

    // 층에 맡기면 고리 · 파티클을 여기서 그리지 않고, 터지는 동안 층에 이 자리를 알린다
    val place = remember { PlaceRef() }
    if (burstLayer != null && burstParts != BurstParts.NONE) {
        DisposableEffect(burstLayer, burstParts) {
            val spot = BurstSpot(elapsedMs = { burst.value }, parts = burstParts, coords = { place.coords })
            burstLayer.spots += spot
            onDispose { burstLayer.spots -= spot }
        }
    }

    Box(modifier.size(size.size).then(if (burstLayer != null) Modifier.onGloballyPositioned { place.coords = it } else Modifier)) {
        if (burstLayer == null && burstParts != BurstParts.NONE) {
            CelebrationBurst(
                elapsedMs = { burst.value },
                modifier = Modifier.matchParentSize(),
                ripple = burstParts.ripple,
                particles = burstParts.particles,
            )
        }
        val box = Modifier
            .matchParentSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
                this.alpha = alpha.value
            }
        if (circle) {
            Image(
                painter = painterResource(pose.res),
                contentDescription = null,
                contentScale = remember(pose) { ZoomCrop(pose.circleZoom) },
                alignment = BiasAlignment(pose.circleBiasX, pose.circleBiasY),
                modifier = box.clip(CircleShape).background(YamuTheme.colors.primaryContainer),
            )
        } else {
            Image(painter = painterResource(pose.res), contentDescription = null, modifier = box)
        }
    }
}

/** 1 → 0.80(눌림) → 1.19(정점) → 0.993 → 1. Compose의 using은 그 키프레임에서 시작하는 구간의 곡선 */
private fun popScale() = keyframes {
    durationMillis = YamuMotion.POP_MS
    1f at 0 using YamuMotion.inOut
    YamuMotion.POP_PRESS_SCALE at YamuMotion.POP_PRESS_AT using YamuMotion.inOut
    YamuMotion.POP_PEAK_SCALE at YamuMotion.POP_PEAK_AT using LinearEasing
    YamuMotion.POP_SETTLE_SCALE at YamuMotion.POP_SETTLE_AT using YamuMotion.inOut
}

/** 눌릴 때 왼쪽으로 기울었다가 정점에서 살짝 넘어간 뒤 바로 섬 */
private fun popRotation() = keyframes {
    durationMillis = YamuMotion.POP_ROTATE_MS
    0f at 0 using YamuMotion.inOut
    YamuMotion.POP_TILT_DEG at YamuMotion.POP_PRESS_AT using YamuMotion.inOut
    YamuMotion.POP_TILT_BACK_DEG at YamuMotion.POP_PEAK_AT using YamuMotion.inOut
}

/** 돌고 있는 팝 하나. 상태가 아니라 끊을 때만 본다 */
private class PopRun { var job: Job? = null }

/** 층에 알릴 캐릭터 자리. 그리기 때만 읽는다 */
private class PlaceRef { var coords: LayoutCoordinates? = null }

internal enum class BurstParts(val ripple: Boolean, val particles: Boolean) {
    ALL(true, true),
    RIPPLE_ONLY(true, false),
    NONE(false, false),
}

/**
 * 연달아 터질 때 줄이기. 앞 버스트가 끝나고 쉬는 시간(PARTICLE_COOLDOWN_MS) 안에 다음이 오면 파티클을 빼고,
 * 그렇게 이어진 것이 PARTICLE_MAX_IN_ROW번을 넘으면 고리도 뺀다. 팝(크기)은 늘 돈다.
 */
internal class BurstStreak {
    private var lastEnd = Long.MIN_VALUE
    private var inRow = 0

    fun next(now: Long): BurstParts {
        val chained = lastEnd != Long.MIN_VALUE && now < lastEnd + YamuMotion.PARTICLE_COOLDOWN_MS
        inRow = if (chained) inRow + 1 else 1
        lastEnd = now + BURST_TOTAL_MS
        return when {
            !chained -> BurstParts.ALL
            inRow <= YamuMotion.PARTICLE_MAX_IN_ROW -> BurstParts.RIPPLE_ONLY
            else -> BurstParts.NONE
        }
    }
}

/** 채우기(Crop)보다 [zoom]배 더 키운다 — 원 안에 전신 대신 얼굴 쪽만 */
private class ZoomCrop(private val zoom: Float) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val s = max(dstSize.width / srcSize.width, dstSize.height / srcSize.height) * zoom
        return ScaleFactor(s, s)
    }
}

/** 불러오는 중 — 회색 막대 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, widthFraction: Float = 1f) {
    Box(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(YamuTheme.colors.surfaceVariant),
    )
}

/** 빈 상태 — 한 문장 + 다음 행동 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = YamuTheme.typography.bodyL, color = YamuTheme.colors.text2, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            GreyChip(actionText, onClick = onAction)
        }
    }
}

/** 오류 — 한 문장 + '다시 시도' + 마지막 반영 시각(금융망 오류 때). 서버에 아직 없는 기능(준비 중)은 다시 해도 같아서 버튼을 숨긴다 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    syncedAt: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = YamuTheme.typography.bodyL, color = YamuTheme.colors.text2, textAlign = TextAlign.Center)
        if (syncedAt != null) {
            Text("마지막 반영 $syncedAt", style = YamuTheme.typography.bodyS, color = YamuTheme.colors.text3)
        }
        if (!isNotReadyMessage(message)) {
            Spacer(Modifier.height(12.dp))
            GreyChip("다시 시도", onClick = onRetry)
        }
    }
}
