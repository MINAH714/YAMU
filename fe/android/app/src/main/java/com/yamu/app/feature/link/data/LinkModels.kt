package com.yamu.app.feature.link.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toManWon
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

// ── 서버 요청 · 응답 — BE card/dto · account/dto와 같은 이름 ──

@Serializable data class BankDto(val code: String? = null, val name: String)

@Serializable data class IssuerDto(val code: String? = null, val name: String, val color: String? = null)

/** 카드번호 원문이 든 유일한 값 — 로그 · 예외 메시지에 찍혀도 끝 4자리만 보이게 BE CardLookupRequest처럼 가린다 */
@Serializable data class CardLookupRequest(val cardNumber: String, val expiryMonth: String, val expiryYear: String) {
    override fun toString() = "CardLookupRequest(cardNumber=************${cardNumber.takeLast(4)}, expiryMonth=$expiryMonth, expiryYear=$expiryYear)"
}

@Serializable data class CardLookupResponse(
    val verificationToken: String,          // 카드 등록에 넘길 1회용 토큰(5분)
    val expiresAt: String? = null,
    val issuer: IssuerDto,
    val product: CardProductDto,
    val maskedNumber: String,
    val holderMatched: Boolean = true,      // BE는 명의가 다르면 CARD_NOT_FOUND로 끝내서 응답이 오면 늘 true
    val hasPriorHistory: Boolean = false,
    val issuerAccount: IssuerAccountDto? = null,
    val paymentDay: Int? = null,            // 신용카드만
    val alreadyRegistered: Boolean = false, // BE는 CARD_ALREADY_REGISTERED로 끝내서 응답이 오면 늘 false
)

@Serializable data class CardProductDto(
    val cardProductId: Long? = null,
    val name: String,
    val cardType: String = "CREDIT",        // CREDIT · CHECK
    val imageUrl: String? = null,
)

/** 카드사가 정한 출금 계좌 — 은행 이름은 오지 않는다 */
@Serializable data class IssuerAccountDto(
    val bankCode: String? = null,
    val maskedAccountNo: String = "",
    val linkedAccountId: Long? = null,
    val linked: Boolean = false,
)

@Serializable data class CardRegisterRequest(val verificationToken: String, val nickname: String? = null)

@Serializable data class CardRegisterResponse(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto,
    val productName: String,
    val cardType: String = "CREDIT",
    val maskedNumber: String,
    val status: String? = null,
    val isDefault: Boolean = false,
    val linkedAccountId: Long? = null,
    val billing: CardBillingDto? = null,    // 신용카드만
    val initialPerformance: InitialPerformanceDto? = null,
    val registeredAt: String? = null,
    val imageUrl: String? = null,
)

@Serializable data class CardBillingDto(
    val paymentDay: Int? = null,
    val closingDayOffset: Int? = null,
    val settlementAccountId: Long? = null,
    val creditLimit: Long? = null,
)

/** source SEED 시드로 집계된 실적 있음 · NONE 없음(새 카드) */
@Serializable data class InitialPerformanceDto(
    val source: String = "NONE",
    val prevMonth: PerformanceMonthDto? = null,
    val currentMonth: PerformanceMonthDto? = null,
)

@Serializable data class PerformanceMonthDto(val yearMonth: String? = null, val recognizedAmount: Long? = null, val benefitUsedAmount: Long? = null)

/** 가입 후 계좌 추가 — 가입 중(1원)은 SignupAccountLinkRequest */
@Serializable data class MemberAccountLinkRequest(val candidateIds: List<String>)

@Serializable data class AccountLinkResponse(
    val accounts: List<AccountDto> = emptyList(),
    val skipped: List<SkippedAccountDto> = emptyList(),
    val verifiedBy: VerifiedByDto? = null,
)

@Serializable data class SkippedAccountDto(val bank: BankDto? = null, val maskedAccountNo: String = "", val reason: String? = null)

@Serializable data class VerifiedByDto(val method: String? = null, val auditLogId: Long? = null, val at: String? = null)

/** #83 `GET /accounts` */
@Serializable data class AccountsResponse(val accounts: List<AccountDto> = emptyList(), val dataSyncedAt: String? = null)

@Serializable data class AccountDto(
    val accountId: Long,
    val bank: BankDto,
    val accountName: String? = null,
    val maskedAccountNo: String,
    val balance: Long? = null,
    val balanceAsOf: String? = null,
    val isPrimary: Boolean = false,
    val linkedCards: List<AccountCardDto> = emptyList(),   // 이 계좌에서 출금하는 등록 카드
)

@Serializable data class AccountCardDto(val userCardId: Long)

/** 보낸 값만 바뀐다 — null은 보내지 않고, 별칭 ""은 지우기. isPrimary false는 서버가 거절해 보내지 않는다(BE AccountUpdateRequest) */
@Serializable data class AccountUpdateRequest(val accountName: String? = null, val isPrimary: Boolean? = null)

/** #7 `GET /cards` */
@Serializable data class CardsResponse(val cards: List<CardDto> = emptyList(), val dataSyncedAt: String? = null)

@Serializable data class CardDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto,
    val productName: String,
    val cardType: String = "CREDIT",
    val maskedNumber: String,
    val status: String? = null,             // PENDING · ACTIVE · INACTIVE · UNREGISTERED
    val issuerStatus: String? = null,       // NORMAL · SUSPENDED · EXPIRED
    val mainBenefits: List<String> = emptyList(),   // 혜택 이름 최대 3개(BE !150 임시 규칙). 오늘 유효한 혜택이 없으면 빈 목록
    val performance: CardPerformanceDto? = null,
    val billingSummary: BillingSummaryDto? = null,
    val isDefault: Boolean = false,
    val imageUrl: String? = null,
)

/**
 * 이번 달 실적. [targetAmount]는 지금 실적보다 큰 기준 중 가장 작은 값이라 한 구간을 넘기면 다음 구간으로 바뀐다(BE !150).
 * 목표가 없으면 [targetAmount] · [achievementRate] · [remainingAmount]가 모두 null
 */
@Serializable data class CardPerformanceDto(
    val yearMonth: String? = null,
    val targetAmount: Long? = null,
    val recognizedAmount: Long = 0,
    val achievementRate: Double? = null,            // %. 소수 한 자리에서 버림, 최대 100
    val remainingAmount: Long? = null,
)

@Serializable data class BillingSummaryDto(val paymentDay: Int? = null)

/** `GET /cards/{id}` · `PATCH /cards/{id}` 응답 — 체크카드는 linkedAccount, 신용카드는 billing만 온다 */
@Serializable data class CardDetailDto(
    val userCardId: Long,
    val nickname: String? = null,           // 별칭이 없으면 상품명이 온다
    val issuer: IssuerDto,
    val productName: String,
    val cardType: String = "CREDIT",
    val maskedNumber: String,
    val status: String? = null,             // ACTIVE 추천 포함 · INACTIVE 추천 제외
    val issuerStatus: String? = null,       // NORMAL · SUSPENDED · EXPIRED
    val isDefault: Boolean = false,
    val linkedAccount: CardLinkedAccountDto? = null,
    val billing: CardDetailBillingDto? = null,
)

@Serializable data class CardLinkedAccountDto(val bankName: String? = null, val maskedAccountNo: String? = null)

@Serializable data class CardDetailBillingDto(
    val paymentDay: Int? = null,
    val settlementAccountId: Long? = null,
    val nextWithdrawal: NextWithdrawalDto? = null,
    val hasUnpaidBill: Boolean = false,
)

@Serializable data class NextWithdrawalDto(val amount: Long? = null, val withdrawalDate: String? = null)

/** 보낸 값만 바뀐다 — null은 보내지 않고, 별칭 ""은 지우기(BE CardUpdateRequest) */
@Serializable data class CardUpdateRequest(val nickname: String? = null, val status: String? = null, val isDefault: Boolean? = null)

/** 등록된 카드 전체를 빠짐없이 원하는 순서로(BE CardOrderRequest) */
@Serializable data class CardOrderRequest(val userCardIds: List<Long>)

// ── 앱 안에서 쓰는 값 ──

/** 카드사 · 은행 표시 — 짧은 이름 + 대표 색(서버 색, 없으면 회색) */
data class Brand(val name: String, val shortName: String, val colorArgb: Long?)

/** 카드사가 정한 출금 계좌 — '신한은행 ·6547' */
data class IssuerAccount(val bankCode: String?, val last4: String, val linked: Boolean) {
    val label: String get() = "${bankNameOf(bankCode) ?: "결제 계좌"} ·$last4"
}

data class CardLookup(
    val verificationToken: String,
    val brand: Brand,
    val productName: String,
    val isCheck: Boolean,
    val maskedNumber: String,
    val last4: String,
    val paymentDay: Int?,
    val hasPriorHistory: Boolean,
    val holderMatched: Boolean,
    val alreadyRegistered: Boolean,
    val issuerAccount: IssuerAccount?,
) {
    /** 명의가 다르거나 이미 등록한 카드 — 설명만 하고 등록을 막는다 */
    val blocked: Boolean get() = !holderMatched || alreadyRegistered
    val accountMissing: Boolean get() = issuerAccount?.linked == false
    val registrable: Boolean get() = !blocked && !accountMissing
}

data class RegisteredCard(
    val userCardId: Long,
    val brand: Brand,
    val productName: String,
    val isCheck: Boolean,
    val last4: String,
    val paymentDay: Int?,
    val prevMonthAmount: Long?,             // 등록 전 실적. 모르면 null(0으로 쓰지 않음)
)

/** 이번에 연결했거나 이미 연결돼 건너뛴 계좌 */
data class AccountRef(val bankCode: String?, val last4: String)

data class AccountLinkResult(val accounts: List<AccountRef>) {
    /** 번호를 지운 뒤라 카드 확인을 다시 할 수 없어 연결 결과로 이 카드의 결제 계좌가 생겼는지 본다 */
    fun covers(account: IssuerAccount): Boolean =
        accounts.any { it.last4 == account.last4 && (it.bankCode == null || it.bankCode == account.bankCode) }
}

data class LinkedAccount(
    val accountId: Long,
    val brand: Brand,
    val name: String,
    val last4: String,
    val balance: Long?,
    val balanceAsOfIso: String?,
    val isPrimary: Boolean,
    val alias: String? = null,              // 사용자가 지은 별칭만. name은 없을 때 기본 이름까지 채운 값
    val cardCount: Int = 0,                 // 이 계좌에서 출금하는 카드 수
)

data class LinkedCard(
    val userCardId: Long,
    val brand: Brand,
    val productName: String,
    val isCheck: Boolean,
    val last4: String,
    val paymentDay: Int?,
    val recognizedAmount: Long?,
    val yearMonth: String?,
    val isDefault: Boolean = false,
    val stateLabel: String? = null,         // 정지 · 만료 · 추천 제외일 때만
    val issuerBlocked: Boolean = false,     // 카드사 정지 · 만료 — 예정 지출의 결제 수단으로 고를 수 없다
    val nickname: String? = null,           // 사용자가 지은 별칭만
    val recommended: Boolean = true,        // 결제 카드 자동 선택에 포함(status ACTIVE)
    val targetAmount: Long? = null,         // 지금 구간의 목표 실적
    val achievementRate: Double? = null,
    val remainingAmount: Long? = null,
    val mainBenefits: List<String> = emptyList(),
) {
    val displayName: String get() = nickname ?: productName
}

/** 카드 관리 줄의 실적 — '목표 70만 원 · 달성률 78.5% · 15만 원 남음'. 목표가 없으면 null */
fun LinkedCard.performanceLine(): String? {
    val target = targetAmount ?: return null
    return buildList {
        add("목표 ${target.toManWon()}")
        achievementRate?.let { add("달성률 ${it.toPercent()}") }
        remainingAmount?.takeIf { it > 0 }?.let { add("${it.toManWon()} 남음") }
    }.joinToString(" · ")
}

/** 카드 관리 줄의 주요 혜택 — '편의점 할인 · 주유 할인 · 카페 할인'. 없으면 null */
fun LinkedCard.benefitsLine(): String? =
    mainBenefits.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** 78.5 → '78.5%', 100.0 → '100%'. 서버가 이미 소수 한 자리에서 버린 값이라 다시 반올림하지 않는다 */
private fun Double.toPercent(): String = "${BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()}%"

/** 카드 상세(M-03) */
data class CardInfo(
    val userCardId: Long,
    val brand: Brand,
    val nickname: String?,                  // 사용자가 지은 별칭만
    val productName: String,
    val isCheck: Boolean,
    val last4: String,
    val recommended: Boolean,               // 결제 카드 자동 선택에 포함
    val stateLabel: String?,
    val issuerBlocked: Boolean,
    val isDefault: Boolean,
    val paymentDay: Int?,
    val settlementAccountId: Long?,         // 신용카드 결제 계좌 — 이름은 계좌 목록에서 찾는다
    val checkAccountLabel: String?,         // 체크카드 연결 계좌
    val nextWithdrawal: NextWithdrawal?,
    val hasOpenBill: Boolean = false,       // 확정 · 미납 청구가 있어 해제할 수 없다. 쌓이는 중인 청구는 여기 없어 해제 때 서버가 막는다
) {
    val displayName: String get() = nickname ?: productName
}

data class NextWithdrawal(val amount: Long, val date: LocalDate)

data class LinkedOverview(val accounts: List<LinkedAccount>, val cards: List<LinkedCard>, val syncedAtIso: String?) {
    val primaryAccount: LinkedAccount? get() = accounts.firstOrNull { it.isPrimary } ?: accounts.firstOrNull()

    /** 확인한 카드가 목록에 있으면 등록된 것 — 카드 번호를 지운 뒤라 카드사 · 상품 · 끝 4자리로 찾는다. 목록에는 등록 전 실적이 없어 null */
    fun registeredCardOf(lookup: CardLookup): RegisteredCard? =
        cards.firstOrNull { it.brand.name == lookup.brand.name && it.productName == lookup.productName && it.last4 == lookup.last4 }
            ?.let { RegisteredCard(it.userCardId, it.brand, it.productName, it.isCheck, it.last4, it.paymentDay, prevMonthAmount = null) }
}

// ── 입력 규칙 ──

/** 카드 입력 규칙 — 서버(CardRegistrationService · CardLookupRequest)와 같게 앱에서 먼저. 형식 오류도 서버의 1분 5번에 세기 때문. 틀리면 안내 문장, 맞으면 null */
object CardValidation {
    const val NUMBER_LENGTH = 16
    const val EXPIRY_LENGTH = 4
    const val NICKNAME_MAX = 20             // 카드 · 계좌 별칭(서버 CardUpdateRequest · AccountUpdateRequest)
    private val NUMBER = Regex("\\d{16}")
    private val EXPIRY = Regex("(0[1-9]|1[0-2])\\d{2}")

    fun number(value: String): String? = if (NUMBER.matches(value)) null else "카드 번호 16자리를 넣어 주세요"

    /** [value]는 숫자만 MMYY. 유효기간은 그 달 말일까지(한국 날짜) */
    fun expiry(value: String, today: LocalDate = LocalDate.now(KST)): String? = when {
        !EXPIRY.matches(value) -> "유효기간을 MM/YY로 넣어 주세요(예: 08/29)"
        YearMonth.of(2000 + value.takeLast(2).toInt(), value.take(2).toInt()).isBefore(YearMonth.from(today)) -> "유효기간이 지난 카드예요"
        else -> null
    }

    /** 앞뒤 공백을 빼고 비면 별칭 없음(서버와 같게) */
    fun nickname(value: String): String? = value.trim().ifEmpty { null }
}

// ── 오류 ──

/** 카드 · 계좌 서버 오류 코드 → 사용자 문장. 모르는 코드면 null(가입 · 인증 · 공통 문장 사용). [reason]은 서버 error.details.reason */
fun cardErrorMessage(code: String, reason: String? = null): String? = when (code) {
    "INVALID_CARD_NUMBER" -> "카드 번호 16자리를 확인해 주세요"
    "CARD_NOT_FOUND" -> "등록할 수 없는 카드예요 · 본인 명의 카드인지 확인해 주세요"
    "CARD_VERIFICATION_FAILED" -> when (reason) {
        "ISSUER_SUSPENDED" -> "카드사에서 정지한 카드예요"
        "ISSUER_EXPIRED" -> "유효기간이 끝난 카드예요"
        "EXPIRY_PASSED" -> "유효기간이 지난 카드예요"
        else -> "유효기간을 확인해 주세요"
    }
    "CARD_ALREADY_REGISTERED" -> "이미 등록한 카드예요"
    "ISSUER_NOT_SUPPORTED" -> "아직 지원하지 않는 카드예요"
    "CARD_ACCOUNT_NOT_SET" -> "카드사에 결제 정보가 없어 등록할 수 없어요"
    "CARD_ACCOUNT_NOT_LINKED" -> "이 카드의 결제 계좌를 먼저 연결해 주세요"
    "VERIFICATION_TOKEN_EXPIRED" -> "확인 시간이 지났어요 · 카드 정보를 다시 넣어 주세요"
    "CARD_HAS_OPEN_BILL" -> "출금이 끝나지 않은 청구가 있어 해제할 수 없어요"
    "CARD_UNREGISTERED" -> "이미 해제한 카드예요"
    "ORDER_INCOMPLETE" -> "다른 기기에서 카드가 바뀌었어요 · 순서를 다시 정해 주세요"
    else -> null
}

/** 카드 등록이 실패했을 때 화면이 할 일 */
enum class CardRegisterFailure {
    /** 확인 토큰이 만료됐거나 그 사이 카드를 쓸 수 없게 됨 — 입력 화면으로 */
    RESTART,
    /** 결제 계좌 연결로 */
    ACCOUNT_NOT_LINKED,
    /** 등록을 막는다 */
    ALREADY_REGISTERED,
    /** 그대로 두고 문장만 — 다시 누를 수 있음 */
    RETRY,
}

fun cardRegisterFailure(code: String): CardRegisterFailure = when (code) {
    "VERIFICATION_TOKEN_EXPIRED", "CARD_VERIFICATION_FAILED", "CARD_ACCOUNT_NOT_SET", "ISSUER_NOT_SUPPORTED" -> CardRegisterFailure.RESTART
    "CARD_ACCOUNT_NOT_LINKED" -> CardRegisterFailure.ACCOUNT_NOT_LINKED
    "CARD_ALREADY_REGISTERED" -> CardRegisterFailure.ALREADY_REGISTERED
    else -> CardRegisterFailure.RETRY
}

// ── 서버 값 → 화면 값 ──

/** '4570-****-****-1234' → '1234' */
fun String.lastDigits(count: Int = 4): String = filter(Char::isDigit).takeLast(count)

/** '#0046FF' → 0xFF0046FF */
fun parseColorArgb(hex: String?): Long? {
    val v = hex?.removePrefix("#")?.takeIf { it.length == 6 || it.length == 8 } ?: return null
    val rgb = v.toLongOrNull(16) ?: return null
    return if (v.length == 6) 0xFF000000 or rgb else rgb
}

/** '신한카드' → '신한', 'KB국민은행' → 'KB국민' */
fun shortBrandName(name: String): String = name.removeSuffix("카드").removeSuffix("은행").ifBlank { name }

fun brandOf(name: String, shortName: String? = null, color: String? = null) =
    Brand(name, shortName ?: shortBrandName(name), parseColorArgb(color))

/** 금융망 은행 코드 → 이름(BE BankNames와 같은 표). 카드 확인 응답에 은행 이름이 없어서 둔다. 모르는 코드면 null */
fun bankNameOf(code: String?): String? = when (code) {
    "001" -> "한국은행"
    "002" -> "산업은행"
    "003" -> "기업은행"
    "004" -> "국민은행"
    "011" -> "농협은행"
    "020" -> "우리은행"
    "023" -> "SC제일은행"
    "027" -> "시티은행"
    "032" -> "대구은행"
    "034" -> "광주은행"
    "035" -> "제주은행"
    "037" -> "전북은행"
    "039" -> "경남은행"
    "045" -> "새마을금고"
    "081" -> "하나은행"
    "088" -> "신한은행"
    "090" -> "카카오뱅크"
    "999" -> "싸피은행"
    else -> null
}

fun CardLookupResponse.toLookup() = CardLookup(
    verificationToken = verificationToken,
    brand = brandOf(issuer.name, color = issuer.color),
    productName = product.name,
    isCheck = product.cardType.equals("CHECK", ignoreCase = true),
    maskedNumber = maskedNumber,
    last4 = maskedNumber.lastDigits(),
    paymentDay = paymentDay,
    hasPriorHistory = hasPriorHistory,
    holderMatched = holderMatched,
    alreadyRegistered = alreadyRegistered,
    issuerAccount = issuerAccount?.let { IssuerAccount(it.bankCode, it.maskedAccountNo.lastDigits(), it.linked) },
)

fun CardRegisterResponse.toRegistered() = RegisteredCard(
    userCardId = userCardId,
    brand = brandOf(issuer.name, color = issuer.color),
    productName = productName,
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    last4 = maskedNumber.lastDigits(),
    paymentDay = billing?.paymentDay,
    prevMonthAmount = initialPerformance?.takeIf { it.source == "SEED" }?.prevMonth?.recognizedAmount,
)

fun AccountLinkResponse.toResult() = AccountLinkResult(
    accounts.map { AccountRef(it.bank.code, it.maskedAccountNo.lastDigits()) } +
        skipped.map { AccountRef(it.bank?.code, it.maskedAccountNo.lastDigits()) },
)

fun AccountDto.toLinked() = LinkedAccount(
    accountId = accountId,
    brand = brandOf(bank.name),
    name = accountName ?: "입출금",
    last4 = maskedAccountNo.lastDigits(),
    balance = balance,
    balanceAsOfIso = balanceAsOf,
    isPrimary = isPrimary,
    alias = accountName,
    cardCount = linkedCards.size,
)

/** 카드 관리 줄 태그 — 카드사가 막은 상태를 먼저. 평소 상태면 null */
fun cardStateLabel(status: String?, issuerStatus: String?): String? = when {
    issuerStatus == "SUSPENDED" -> "카드사 정지"
    issuerStatus == "EXPIRED" -> "유효기간 만료"
    status == "INACTIVE" -> "추천 제외"
    else -> null
}

fun CardDto.toLinked() = LinkedCard(
    userCardId = userCardId,
    brand = brandOf(issuer.name, color = issuer.color),
    productName = productName,
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    last4 = maskedNumber.lastDigits(),
    paymentDay = billingSummary?.paymentDay,
    recognizedAmount = performance?.recognizedAmount,
    yearMonth = performance?.yearMonth,
    isDefault = isDefault,
    stateLabel = cardStateLabel(status, issuerStatus),
    issuerBlocked = issuerStatus == "SUSPENDED" || issuerStatus == "EXPIRED",
    nickname = nickname?.takeIf { it != productName },
    recommended = status != "INACTIVE",
    targetAmount = performance?.targetAmount,
    achievementRate = performance?.achievementRate,
    remainingAmount = performance?.remainingAmount,
    mainBenefits = mainBenefits,
)

fun CardDetailDto.toInfo() = CardInfo(
    userCardId = userCardId,
    brand = brandOf(issuer.name, color = issuer.color),
    nickname = nickname?.takeIf { it != productName },
    productName = productName,
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    last4 = maskedNumber.lastDigits(),
    recommended = status != "INACTIVE",
    stateLabel = cardStateLabel(status, issuerStatus),
    issuerBlocked = issuerStatus == "SUSPENDED" || issuerStatus == "EXPIRED",
    isDefault = isDefault,
    paymentDay = billing?.paymentDay,
    settlementAccountId = billing?.settlementAccountId,
    checkAccountLabel = linkedAccount?.let { a -> listOfNotNull(a.bankName, a.maskedAccountNo?.lastDigits()?.let { "·$it" }).joinToString(" ").ifEmpty { null } },
    nextWithdrawal = billing?.nextWithdrawal?.let { n ->
        val amount = n.amount ?: return@let null
        val date = n.withdrawalDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@let null
        NextWithdrawal(amount, date)
    },
    hasOpenBill = billing?.nextWithdrawal != null || billing?.hasUnpaidBill == true,
)
