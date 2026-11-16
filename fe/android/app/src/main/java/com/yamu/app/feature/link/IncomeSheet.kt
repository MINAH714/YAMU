package com.yamu.app.feature.link

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.AmountLimit
import com.yamu.app.feature.link.data.IncomeAccount
import com.yamu.app.feature.link.data.IncomeInput

@Composable
internal fun IncomeSheet(
    current: IncomeInput?,
    accounts: List<IncomeAccount>,
    defaultAccount: IncomeAccount?,
    onDismiss: () -> Unit,
    onSave: (IncomeInput?) -> Unit,
    busy: Boolean = false,
    locked: Boolean = false,
    error: String? = null,
    allowRemove: Boolean = true,
    note: String? = null,
) {
    val c = YamuTheme.colors
    var amount by remember { mutableStateOf(AmountLimit.CASHFLOW.clampInput(current?.amount?.toString().orEmpty())) }
    var day by remember { mutableStateOf(current?.day?.toString().orEmpty()) }
    var accountId by remember {
        mutableStateOf(if (locked) current?.accountId else current?.accountId?.takeIf { id -> accounts.any { it.accountId == id } } ?: defaultAccount?.accountId)
    }
    var tried by remember { mutableStateOf(false) }
    val amountError = incomeAmountError(amount)
    val dayError = incomeDayError(day)
    YamuBottomSheet(onDismiss = { if (!busy) onDismiss() }) {
        Text("월급", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "금액(원)",
            value = amount,
            onValueChange = { v -> if (!busy && !locked) amount = AmountLimit.CASHFLOW.clampInput(v) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = IncomeAmountFormat,
            supporting = incomeAmountCaption(amount.toLongOrNull()),
            error = amountError.takeIf { tried },
        )
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "매월 받는 날",
            value = day,
            onValueChange = { v -> if (!busy && !locked) day = v.filter(Char::isDigit).take(2) },
            placeholder = "예: 25",   // 숫자만 두면 들어간 값처럼 보여 비운 채 저장하다 막혔다(S15P31E103-431)
            supporting = "31을 넣으면 매월 말일이에요",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            error = dayError.takeIf { tried },
        )
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(YamuSpacing.x4))
            Text("받는 계좌", style = YamuTheme.typography.bodyS, color = c.text2)
            accounts.forEach { a ->
                ListRow(
                    title = a.label,
                    value = if (a.accountId == accountId) "선택" else null,
                    valueColor = c.primary,
                    dense = true,
                    onClick = { if (!busy && !locked) accountId = a.accountId },
                    modifier = Modifier.semantics { contentDescription = a.label + if (a.accountId == accountId) ", 고름" else "" },
                )
            }
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        note?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text2) }
        error?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text2) }
        if (note != null || error != null) Spacer(Modifier.height(YamuSpacing.x4))
        PrimaryButton(if (busy) "저장 중" else if (locked) "같은 내용으로 다시 저장" else "저장", enabled = !busy, onClick = {
            tried = true
            val id = accountId
            if (amountError == null && dayError == null && id != null) onSave(IncomeInput(amount.toLong(), day.toInt(), id))
        })
        if (allowRemove && current != null) {
            Spacer(Modifier.height(YamuSpacing.x2))
            NeutralButton("월급 넣지 않기", onClick = { onSave(null) })
        }
    }
}

