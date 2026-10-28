package com.yamu.app.feature.payment

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.StatusTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.yamu.app.core.designsystem.theme.YamuTheme

// 결제 코드 그리기 — 같은 토큰으로 QR(qrValue)과 바코드 Code128(barcodeValue). 테마와 무관하게 흰 바탕 위 검정(qrInk · qrBackground)
// 코드 값은 화면 읽기(TalkBack)에 읽히지 않게 설명만 둔다

/** QR — 정사각형, 칸마다 사각형 */
/** '모의 결제' — 앱바 오른쪽 주황 태그(P-06 · P-06c · P-07 · P-08) */
@Composable
fun MockPayTag() {
    StatusTag("모의 결제", tone = TagTone.Warning, modifier = Modifier.padding(end = 12.dp))
}

@Composable
fun PayQrCode(value: String, modifier: Modifier = Modifier) {
    val matrix = remember(value) {
        runCatching {
            QRCodeWriter().encode(
                value, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    val ink = YamuTheme.colors.qrInk
    val paper = YamuTheme.colors.qrBackground
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "결제 QR 코드" }) {
        drawRect(paper)
        val m = matrix ?: return@Canvas
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** 바코드 Code128 — 가로로 긴 막대 */
@Composable
fun PayBarcode(value: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(value) {
        runCatching { Code128Writer().encode(value, BarcodeFormat.CODE_128, 0, 1) }.getOrNull()
    }
    val ink = YamuTheme.colors.qrInk
    val paper = YamuTheme.colors.qrBackground
    Canvas(modifier.semantics { contentDescription = "결제 바코드" }) {
        drawRect(paper)
        val m = matrix ?: return@Canvas
        val bar = size.width / m.width
        for (x in 0 until m.width) {
            if (m[x, 0]) drawRect(ink, Offset(x * bar, 0f), Size(bar + 0.5f, size.height))
        }
    }
}
