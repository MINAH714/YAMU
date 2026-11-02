package com.yamu.app.feature.auth

import androidx.compose.foundation.background
import com.yamu.app.core.designsystem.component.StartStepper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthValidation
import com.yamu.app.feature.auth.data.LoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 이메일 · 비밀번호 폼 상태 — 가입(A-05). 이메일 로그인은 없어졌고(DEC-001), 가입도 Task 8.2.9에서 휴대폰 본인인증으로 바뀐다 */
data class CredentialFormState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val showErrors: Boolean = false,
    val submitting: Boolean = false,
    val serverError: String? = null,
    val done: LoginResult? = null,
)

@HiltViewModel
class SignupViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(CredentialFormState())
    val state: StateFlow<CredentialFormState> = _state.asStateFlow()

    fun onName(v: String) = _state.update { it.copy(name = v, serverError = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, serverError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, serverError = null) }

    fun submit() {
        val s = _state.value
        val valid = AuthValidation.name(s.name) == null && AuthValidation.email(s.email) == null && AuthValidation.password(s.password) == null
        if (!valid) return _state.update { it.copy(showErrors = true) }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, serverError = null) }
            when (val r = auth.signup(s.name, s.email, s.password)) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, done = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, serverError = r.error.authMessage()) }
            }
        }
    }
}

/** A-05 가입 정보(이름 · 이메일 · 비밀번호) → #67 → #68 */
@Composable
fun SignupInfoRoute(onBack: () -> Unit, onSignedUp: () -> Unit, viewModel: SignupViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    if (s.done != null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onSignedUp() }
    }
    val c = YamuTheme.colors
    Scaffold(
        modifier = Modifier.imePadding(),   // 키보드가 올라와도 [가입하기]가 키보드 위에
        topBar = {
            Column(Modifier.background(c.surface)) {
                YamuTopBar("회원가입", onBack = onBack)
                StartStepper(step = 2, total = 3, label = "가입 정보")
            }
        },
        bottomBar = { BottomCta { PrimaryButton("가입하기", onClick = viewModel::submit, loading = s.submitting) } },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("가입 정보를 넣어 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(24.dp))
            YamuTextField(
                label = "이름", value = s.name, onValueChange = viewModel::onName, placeholder = "김야무",
                supporting = "계좌를 연결할 때 예금주와 맞춰 봐요",
                error = if (s.showErrors) AuthValidation.name(s.name) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(20.dp))
            YamuTextField(
                label = "이메일", value = s.email, onValueChange = viewModel::onEmail, placeholder = "yamu@ssafy.com",
                error = if (s.showErrors) AuthValidation.email(s.email) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(20.dp))
            YamuTextField(
                label = "비밀번호", value = s.password, onValueChange = viewModel::onPassword,
                supporting = "영문 · 숫자 · 특수문자를 섞어 8자 이상",
                error = if (s.showErrors) AuthValidation.password(s.password) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = PasswordVisualTransformation(),
            )
            s.serverError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error)
            }
        }
    }
}
