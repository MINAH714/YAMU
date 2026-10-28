package com.yamu.app.feature.auth

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.StartStepper
import com.yamu.app.core.designsystem.component.QuietLink
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthValidation
import com.yamu.app.feature.auth.data.LoginResult
import com.yamu.app.feature.auth.data.authErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 이메일 · 비밀번호 폼 상태 — 가입(A-05) · 로그인(A-03)이 같이 쓴다 */
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

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(CredentialFormState())
    val state: StateFlow<CredentialFormState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, serverError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, serverError = null) }

    fun submit() {
        val s = _state.value
        if (AuthValidation.email(s.email) != null || s.password.isEmpty()) return _state.update { it.copy(showErrors = true) }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, serverError = null) }
            when (val r = auth.login(s.email, s.password)) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, done = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(submitting = false, serverError = r.error.authMessage()) }
            }
        }
    }
}

/** A-03 로그인 — 성공하면 초기 설정이 끝났는지에 따라 홈 / 연결로 */
@Composable
fun LoginRoute(
    onBack: (() -> Unit)?,
    onLoggedIn: (onboardingCompleted: Boolean) -> Unit,
    onSignup: () -> Unit,
    onFindPassword: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    s.done?.let { done -> androidx.compose.runtime.LaunchedEffect(done) { onLoggedIn(done.onboardingCompleted) } }
    val c = YamuTheme.colors
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { YamuTopBar("로그인", onBack = onBack) },
        bottomBar = { BottomCta { PrimaryButton("로그인", onClick = viewModel::submit, loading = s.submitting) } },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("다시 만나서 반가워요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(4.dp))
            Text("이메일로 로그인해 주세요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(20.dp))   // 머리 아래 28(프로토타입 A-03 .hd) — 입력칸이 라벨 자리 8을 위에 둔다
            YamuTextField(
                label = "이메일", value = s.email, onValueChange = viewModel::onEmail,
                error = if (s.showErrors) AuthValidation.email(s.email) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))   // 칸 사이 24(.tf.mt24) − 라벨 자리 8
            var showPassword by rememberSaveable { mutableStateOf(false) }
            YamuTextField(
                label = "비밀번호", value = s.password, onValueChange = viewModel::onPassword,
                error = if (s.showErrors && s.password.isEmpty()) "비밀번호를 넣어 주세요" else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) YamuIcons.VisibilityOff else YamuIcons.Visibility,
                            contentDescription = if (showPassword) "비밀번호 숨기기" else "비밀번호 보기",
                            tint = c.text3,
                        )
                    }
                },
            )
            s.serverError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // 글자 끝을 입력칸 끝선에 맞춘다(링크 안쪽 여백 4만큼 밖으로 — 프로토타입 .btn.link 음수 여백)
                QuietLink("비밀번호 찾기", onClick = onFindPassword, modifier = Modifier.offset(x = (-4).dp))
                QuietLink("회원가입", onClick = onSignup, modifier = Modifier.offset(x = 4.dp))
            }
        }
    }
}
