package com.yamu.app.feature.link

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.yamu.app.core.format.toWon

/** 반올림 없이 만 원과 나머지 원을 나눠 입력한 금액 전체를 읽는다. */
internal fun incomeAmountCaption(amount: Long?): String? {
    if (amount == null || amount <= 0) return null
    val man = amount / 10_000
    val remainder = amount % 10_000
    return when {
        man == 0L -> "${remainder.toWon()}원"
        remainder == 0L -> "${man.toWon()}만 원"
        else -> "${man.toWon()}만 ${remainder.toWon()}원"
    }
}

/** 표시만 천 단위로 나눈다. 입력·검증·저장 값은 숫자 그대로이며 중간 커서와 선택 범위도 원문에 대응한다. */
internal object IncomeAmountFormat : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = text.text.reversed().chunked(3).joinToString(",").reversed()
        val originalOffsets = listOf(0) + formatted.mapIndexedNotNull { index, char ->
            (index + 1).takeIf { char != ',' }
        }
        return TransformedText(AnnotatedString(formatted), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = originalOffsets[offset.coerceIn(0, text.length)]
            override fun transformedToOriginal(offset: Int): Int = formatted.take(offset.coerceIn(0, formatted.length)).count { it != ',' }
        })
    }
}
