package com.yamu.app.feature.auth

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.Carrier
import com.yamu.app.feature.auth.data.CodeSent
import com.yamu.app.feature.auth.data.IdentityDraft
import com.yamu.app.feature.auth.data.SignupMode
import com.yamu.app.feature.auth.data.SignupOneWon
import com.yamu.app.feature.auth.data.SignupRepository
import com.yamu.app.feature.auth.data.SignupRestart
import com.yamu.app.feature.auth.data.phoneVerifyGuide
import com.yamu.app.feature.link.data.brandOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** 가입 흐름 화면 상태 — 본인인증 다시 확인(B-12) · 다시 시작 안내(B-13) · 계좌 미리 고르기 · 1원 화면(B-16) · 오류 문장(R-7) */
class SignupFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val signup = TestSignup()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun PhoneVerifyViewModel.fillAndSend() {
        onName("김야무")
        onBirth("19980101")
        onCarrier(Carrier.SKT)
        onPhone("01012345678")
        send()
    }

    // ── B-12 본인인증을 마친 뒤 돌아와 다시 누르기 ──

    @Test fun `인증을 마친 뒤 돌아와 다시 누르면 서버를 부르지 않고 받아 둔 토큰으로 다음 단계로 간다`() = runTest(dispatcher) {
        signup.termsAgreed = true
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(SignupNext.ACCOUNTS, vm.state.value.next)
        assertEquals(SignupMode.SIGNUP, vm.state.value.verified)
        vm.navigated()

        // 계좌 화면에서 뒤로 와 버튼을 다시 누른다(확인 · 다음 어느 쪽이든)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(SignupNext.ACCOUNTS, vm.state.value.next)
        vm.navigated()
        vm.proceed()
        assertEquals(SignupNext.ACCOUNTS, vm.state.value.next)
        assertEquals(1, signup.confirmCalls)
        assertNull(vm.state.value.error)
    }

    // ── QA 11/10 인증번호 자동 입력 ──

    @Test fun `인증번호를 받으면 문자 알림과 함께 칸을 채우고 확인은 버튼을 누를 때만 보낸다`() = runTest(dispatcher) {
        signup.termsAgreed = true
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("123456", s.code)
        assertTrue(s.banner.orEmpty().contains("123456"))
        assertEquals(0, signup.confirmCalls)       // 채우기만 하고 서버에 확인을 보내지 않는다
        assertNull(s.next)

        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, signup.confirmCalls)
        assertEquals(SignupNext.ACCOUNTS, vm.state.value.next)
    }

    @Test fun `다시 받으면 새 번호로 바꿔 넣는다`() = runTest(dispatcher) {
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("12")                            // 지우다 말았다

        signup.demoCode = "654321"
        vm.send()                                  // 다시 받기
        advanceUntilIdle()
        assertEquals("654321", vm.state.value.code)
        assertFalse(vm.state.value.codeError)
    }

    @Test fun `시연 번호가 없으면 칸을 비워 두고 알림도 띄우지 않는다`() = runTest(dispatcher) {
        signup.demoCode = null
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        assertEquals("", vm.state.value.code)
        assertNull(vm.state.value.banner)
        assertTrue(vm.state.value.sent != null)    // 받은 건 맞다 — 손으로 넣는다
    }

    @Test fun `손으로 고쳐 틀리면 칸을 비우고 알림을 누르면 받은 번호로 다시 채운다`() = runTest(dispatcher) {
        signup.confirmResult = ApiResult.Failure(ApiError.from(400, "VERIFICATION_CODE_MISMATCH"))
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("111111")
        vm.confirm()
        advanceUntilIdle()
        assertEquals("", vm.state.value.code)
        assertTrue(vm.state.value.codeError)
        assertTrue(vm.state.value.sent != null)    // 같은 번호로 다시 넣으면 된다

        vm.fillFromBanner()
        assertEquals("123456", vm.state.value.code)
        assertFalse(vm.state.value.codeError)
    }

    @Test fun `1원 숫자는 계좌 확인이라 시연 숫자가 와도 칸을 채우지 않고 알림만 띄운다`() = runTest(dispatcher) {
        signup.mode = SignupMode.SIGNUP
        signup.oneWon = oneWon(demoCode = "1004")
        val vm = SignupOneWonViewModel(signup)
        assertEquals("", vm.state.value.code)
        assertTrue(vm.state.value.banner.orEmpty().contains("1004"))
    }

    // ── B-03 시연 등록자가 아닐 때 ──

    @Test fun `시연 등록자가 아니면 어느 경우인지 말하지 않고 칸은 그대로 둔 채 인증번호만 다시 받게 한다`() = runTest(dispatcher) {
        signup.termsAgreed = true
        signup.confirmResult = ApiResult.Failure(ApiError.from(404, "DEMO_USER_NOT_FOUND"))
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("입력한 정보로는 가입할 수 없어요 · 등록된 이름 · 생년월일 · 번호인지 확인해 주세요", s.error)
        assertNull(s.sent)                         // '인증번호 받기'로 돌아간다
        assertFalse(s.codeError)                   // 인증번호가 틀린 게 아니다
        assertEquals("김야무", s.name)             // 오타만 고치면 되게 칸은 그대로
        assertEquals("01012345678", s.phone)
        assertNull(s.next)                         // 첫 화면으로 보내지 않는다
    }

    @Test fun `첫 화면 로그인으로 온 본인 확인은 가입이 아닌 문장으로`() = runTest(dispatcher) {
        signup.termsAgreed = false
        signup.confirmResult = ApiResult.Failure(ApiError.from(404, "DEMO_USER_NOT_FOUND"))
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()

        assertEquals("입력한 정보로는 본인 확인을 할 수 없어요 · 등록된 이름 · 생년월일 · 번호인지 확인해 주세요", vm.state.value.error)
    }

    @Test fun `본인인증 안내 — 가입은 미리 등록된 분만 된다고 인증번호 받기 전에 알린다`() {
        assertTrue(phoneVerifyGuide(signup = true).startsWith("야무는 시연용으로 미리 등록된 분만 가입할 수 있어요"))
        assertEquals("시연에 등록된 이름 · 생년월일 · 휴대폰 번호를 넣어 주세요", phoneVerifyGuide(signup = false))
    }

    @Test fun `재등록도 다시 누르면 이 폰 등록으로 다시 간다`() = runTest(dispatcher) {
        signup.confirmResult = ApiResult.Success(SignupMode.DEVICE_RECOVERY)
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()
        vm.navigated()
        vm.proceed()
        assertEquals(SignupNext.RECOVERY, vm.state.value.next)
        assertEquals(1, signup.confirmCalls)
    }

    @Test fun `인증을 마친 뒤 칸을 고치면 인증을 버리고 인증번호부터 다시 받게 한다`() = runTest(dispatcher) {
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()
        vm.navigated()

        vm.onPhone("01012345679")
        val s = vm.state.value
        assertNull(s.verified)
        assertNull(s.sent)
        assertEquals("", s.code)
        assertEquals(1, signup.dropped)          // 저장소의 가입 진행 토큰도 버린다(약관으로 돌아가도 옛 토큰으로 못 감)
        assertNull(signup.mode)
        vm.proceed()
        assertNull(vm.state.value.next)
    }

    @Test fun `확인하는 사이 칸을 고치면 받은 인증을 버린다`() = runTest(dispatcher) {
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        vm.onName("김다른")                      // 응답이 오기 전에 고침
        advanceUntilIdle()
        assertNull(vm.state.value.verified)
        assertNull(vm.state.value.next)
        assertEquals(1, signup.dropped)
    }

    @Test fun `인증을 마친 뒤 번호를 고치고 약관을 거쳐 다시 들어와도 고친 번호가 남는다`() = runTest(dispatcher) {
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()
        vm.navigated()
        vm.onPhone("01011112222")                 // 인증번호를 다시 받기 전에 약관으로 뒤로 간다(이 화면 상태는 사라진다)

        val again = PhoneVerifyViewModel(signup)  // 약관 '동의하고 계속'으로 새로 열린 본인인증 화면
        assertEquals("01011112222", again.state.value.phone)
        assertEquals("김야무", again.state.value.name)
        assertNull(again.state.value.verified)
        again.send()
        advanceUntilIdle()
        assertEquals("01011112222", signup.requestedPhone)   // 옛 번호로 보내지 않는다
    }

    @Test fun `인증을 마친 채 화면이 새로 열리면 마친 상태로 이어 간다`() = runTest(dispatcher) {
        signup.termsAgreed = true
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()

        val again = PhoneVerifyViewModel(signup)
        assertEquals(SignupMode.SIGNUP, again.state.value.verified)
        assertEquals("01012345678", again.state.value.phone)
        again.proceed()
        assertEquals(SignupNext.ACCOUNTS, again.state.value.next)
        assertEquals(1, signup.confirmCalls)
    }

    @Test fun `같은 값을 다시 넣는 건 고친 게 아니라 인증을 버리지 않는다`() = runTest(dispatcher) {
        val vm = PhoneVerifyViewModel(signup)
        vm.fillAndSend()
        advanceUntilIdle()
        vm.onCode("123456")
        vm.confirm()
        advanceUntilIdle()
        vm.onCarrier(Carrier.SKT)
        assertEquals(SignupMode.SIGNUP, vm.state.value.verified)
        assertEquals(0, signup.dropped)
    }

    // ── B-13 본인인증부터 다시 ──

    @Test fun `가입 진행 시간이 지나 다시 오면 넣었던 칸과 약관 동의를 남기고 까닭을 띄운다`() = runTest(dispatcher) {
        signup.termsAgreed = true
        signup.draft = IdentityDraft("김야무", "19980101", Carrier.LGU, "01012345678")
        signup.restart(SignupRestart.EXPIRED)

        val vm = PhoneVerifyViewModel(signup)
        val s = vm.state.value
        assertEquals("김야무", s.name)
        assertEquals("19980101", s.birth)
        assertEquals(Carrier.LGU, s.carrier)
        assertEquals("01012345678", s.phone)
        assertTrue(s.fromTerms)                   // 제목 '회원가입'과 단계 표시가 그대로
        assertNull(s.verified)
        assertEquals(restartNotice(SignupRestart.EXPIRED, keptInput = true), s.notice)

        // 까닭은 한 번만 — 새 인증번호를 받으면 지운다
        assertNull(PhoneVerifyViewModel(signup).state.value.notice)
        vm.send()
        advanceUntilIdle()
        assertNull(vm.state.value.notice)
    }

    @Test fun `다시 시작 안내는 까닭과 할 일을 말한다`() {
        val expired = restartNotice(SignupRestart.EXPIRED, keptInput = true)
        assertTrue(expired.contains("30분"))
        assertTrue(expired.contains("그대로"))
        val lost = restartNotice(SignupRestart.LOST, keptInput = false)
        assertTrue(lost.contains("앱이 다시 켜져"))
        assertFalse(lost.contains("그대로"))
    }

    @Test fun `1원 화면에 진행 상태가 없으면 까닭을 남기고 본인인증부터`() = runTest(dispatcher) {
        val vm = SignupOneWonViewModel(signup)
        assertTrue(vm.state.value.restart)
        assertEquals(SignupRestart.LOST, signup.takeRestartReason())
    }

    // ── B-13 1원 남은 시간 · B-16 1원 문장 ──

    @Test fun `1원 화면은 저장소가 적은 1원 요청 시각부터 남은 시간을 센다`() = runTest(dispatcher) {
        signup.mode = SignupMode.SIGNUP
        signup.oneWon = oneWon(sentAtMillis = 1_000L, validSeconds = 300)
        val vm = SignupOneWonViewModel(signup)
        assertEquals(1_000L, vm.state.value.sentAtMillis)
        assertEquals(300, vm.state.value.info?.validSeconds)
        assertFalse(vm.state.value.restart)
    }

    @Test fun `1원 제목은 끝자리 숫자에 조사를 붙이지 않는다`() {
        assertEquals("신한은행 ·4450 계좌로\n1원을 보냈어요", oneWon().headline())
        assertEquals("·4453 계좌로\n1원을 보냈어요", oneWon(bankName = "", last4 = "4453").headline())
        assertEquals("계좌로\n1원을 보냈어요", oneWon(bankName = " ", last4 = "").headline())
    }

    // ── B-16 계좌가 하나면 미리 고르기 ──

    @Test fun `가입에서도 연결할 계좌가 하나면 미리 고른다`() = runTest(dispatcher) {
        signup.mode = SignupMode.SIGNUP
        signup.candidatesResult = ApiResult.Success(listOf(candidate("cand_kb")))
        val vm = SignupAccountsViewModel(signup)
        advanceUntilIdle()
        assertEquals(listOf("cand_kb"), vm.state.value.selected)
        assertEquals("cand_kb", vm.state.value.primary)
    }

    @Test fun `계좌가 둘 이상이면 고르지 않고 둔다`() = runTest(dispatcher) {
        signup.mode = SignupMode.SIGNUP
        signup.candidatesResult = ApiResult.Success(listOf(candidate("cand_kb"), candidate("cand_sh")))
        val vm = SignupAccountsViewModel(signup)
        advanceUntilIdle()
        assertTrue(vm.state.value.selected.isEmpty())
        assertNull(vm.state.value.primary)
    }

    // ── R-7 가입 오류가 공통 문장으로 떨어지지 않게 ──

    @Test fun `이미 가입한 사람의 409는 같은 요청 처리 중이 아니라 로그인으로 이끈다`() {
        val message = ApiError.from(409, "ALREADY_REGISTERED").signupMessage()
        assertEquals("이미 가입을 마쳤어요 · 처음 화면의 로그인으로 이 폰을 등록해 주세요", message)
    }

    @Test fun `가입 · 재등록에서 나는 코드는 모두 가입 · 인증 문장이 받는다`() {
        val common = setOf("같은 요청이 이미 처리되고 있어요", "입력한 내용을 다시 확인해 주세요", "찾을 수 없어요", "다시 로그인해 주세요")
        listOf(
            400 to "INVALID_PIN_FORMAT", 400 to "INVALID_PUBLIC_KEY", 409 to "DEVICE_LIMIT_EXCEEDED", 409 to "ALREADY_REGISTERED",
            409 to "SIGNUP_STEP_INCOMPLETE", 404 to "ACCOUNT_NOT_FOUND_AT_BANK", 400 to "ACCOUNT_NOT_SUPPORTED",
            409 to "IDEMPOTENCY_KEY_REUSED", 409 to "IDEMPOTENCY_IN_PROGRESS", 404 to "DEMO_USER_NOT_FOUND",
        ).forEach { (status, code) ->
            val message = ApiError.from(status, code).signupMessage()
            assertFalse("$code → $message", message in common)
        }
        // 가입 중엔 마이 화면에 갈 수 없다
        assertFalse(ApiError.from(409, "DEVICE_LIMIT_EXCEEDED").signupMessage().contains("마이"))
    }

    private fun oneWon(
        bankName: String = "신한은행",
        last4: String = "4450",
        sentAtMillis: Long = 0L,
        validSeconds: Int = 300,
        demoCode: String? = null,
    ) = SignupOneWon(bankName, last4, "YAMU", 4, null, false, demoCode, validSeconds = validSeconds, sentAtMillis = sentAtMillis)

    private fun candidate(id: String) = AccountCandidate(id, brandOf("KB국민은행"), "KB국민 입출금", "1234", alreadyLinked = false)

    /** 서버처럼 맞힌 인증번호는 지운다 — 두 번째 확인은 VERIFICATION_EXPIRED */
    private class TestSignup : SignupRepository {
        var confirmCalls = 0
        var dropped = 0
        var confirmResult: ApiResult<SignupMode> = ApiResult.Success(SignupMode.SIGNUP)
        var candidatesResult: ApiResult<List<AccountCandidate>> = ApiResult.Success(emptyList())
        var requestedPhone: String? = null
        /** 시연 서버가 응답에 실어 주는 인증번호 — null이면 운영처럼 빠진다 */
        var demoCode: String? = "123456"
        private var restartReason: SignupRestart? = null

        override var mode: SignupMode? = null
        override var name: String? = null
        override var termsAgreed = false
        override var draft = IdentityDraft()
        override var recoveryBiometric = false
        override var pinStaged = false
        override var oneWon: SignupOneWon? = null

        override suspend fun requestCode(name: String, birthDate: LocalDate, carrier: Carrier, phoneNumber: String): ApiResult<CodeSent> {
            requestedPhone = phoneNumber
            return ApiResult.Success(CodeSent("pv_$name$phoneNumber", demoCode))
        }

        override suspend fun confirmCode(verificationId: String, code: String): ApiResult<SignupMode> {
            confirmCalls++
            if (confirmCalls > 1) return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_EXPIRED"))
            return confirmResult.also { if (it is ApiResult.Success) mode = it.data }
        }

        override suspend fun candidates() = candidatesResult
        override suspend fun sendOneWon(candidateIds: List<String>, primaryId: String): ApiResult<SignupOneWon> = error("안 씀")
        override suspend fun resendOneWon(): ApiResult<SignupOneWon> = error("안 씀")
        override suspend fun confirmOneWon(code: String): ApiResult<Unit> = error("안 씀")
        override suspend fun setPin(pin: String): ApiResult<Unit> = error("안 씀")
        override suspend fun registerDevice(biometric: Boolean): ApiResult<Unit> = error("안 씀")

        override fun dropVerification() {
            dropped++
            mode = null
            oneWon = null
        }

        override fun restart(reason: SignupRestart) {
            mode = null
            oneWon = null
            restartReason = reason
        }

        override fun takeRestartReason(): SignupRestart? = restartReason.also { restartReason = null }

        override fun clear() {
            restart(SignupRestart.LOST)
            restartReason = null
            draft = IdentityDraft()
            termsAgreed = false
        }
    }
}
