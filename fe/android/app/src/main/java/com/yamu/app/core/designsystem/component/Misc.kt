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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
 * 자리 크기는 둘 다 [size] 정사각형이라 둘레 배치는 그대로. 움직임은 보류 — 지금은 들어올 때 한 번 볼록
 */
@Composable
fun CharacterImage(
    pose: YamuPose,
    modifier: Modifier = Modifier,
    size: CharacterSize = CharacterSize.M,
    animate: Boolean = false,
    circle: Boolean = size == CharacterSize.S,
) {
    val scale = remember { Animatable(if (animate) 0.9f else 1f) }
    LaunchedEffect(animate) {
        if (animate) scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val box = modifier
        .size(size.size)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
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

/** 오류 — 한 문장 + '다시 시도' + 마지막 반영 시각(금융망 오류 때) */
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
        Spacer(Modifier.height(12.dp))
        GreyChip("다시 시도", onClick = onRetry)
    }
}
