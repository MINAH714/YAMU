package com.yamu.app.feature.link.data

import kotlinx.serialization.Serializable

// ── 서버 요청 · 응답 — 보완안 추가 1~5(목록에서 골라 연결) · #31(1원, 세션으로 옮김) · #83 · #7. 09 문서 5-2 ──

@Serializable data class InstitutionsResponse(val institutions: List<InstitutionDto> = emptyList())

@Serializable data class InstitutionDto(
    val institutionId: String,
    val name: String,
    val type: String,                       // BANK · CARD
    val shortName: String? = null,
    val color: String? = null,              // '#0046FF'
    val hasMyProducts: Boolean = false,
    val linkedCount: Int = 0,
    val selectable: Boolean = true,
)

@Serializable data class CreateLinkSessionRequest(val institutionIds: List<String>, val purpose: String)

@Serializable data class LinkSessionDto(
    val linkSessionId: String,
    val status: String,                     // SELECTED · VERIFIED · COMPLETED · EXPIRED
    val purpose: String? = null,
    val institutionIds: List<String> = emptyList(),
    val ownAccountCheck: OwnAccountCheckDto? = null,
    val terms: List<TermsDto> = emptyList(),
    val expiresAt: String? = null,
)

@Serializable data class OwnAccountCheckDto(
    val required: Boolean = false,
    val bank: BankDto? = null,
    val maskedAccountNo: String? = null,
    val verifiedAt: String? = null,
)

@Serializable data class BankDto(val code: String? = null, val name: String)

@Serializable data class IssuerDto(val code: String? = null, val name: String, val color: String? = null)

@Serializable data class TermsDto(val termsCode: String, val version: String, val title: String, val required: Boolean = true)

/** #31 응답 이름 그대로 + 보완안 remainingAttempts · resendAvailableAt */
@Serializable data class OneWonResponse(
    val authRequestedAt: String? = null,
    val authExpiresAt: String? = null,
    val depositorNamePrefix: String = "YAMU",
    val codeLength: Int = 4,
    val hint: String? = null,
)

@Serializable data class AgreementDto(val termsCode: String, val version: String)

@Serializable data class LinkVerifyRequest(
    val authCode: String? = null,
    val agreements: List<AgreementDto>,
    val withdrawalConsent: Boolean = true,
)

@Serializable data class LinkVerifyResponse(val verified: Boolean = true, val ownAccountVerifiedAt: String? = null)

@Serializable data class LinkAssetsResponse(
    val accounts: List<AccountAssetDto> = emptyList(),
    val cards: List<CardAssetDto> = emptyList(),
)

@Serializable data class AccountAssetDto(
    val assetId: String,
    val bank: BankDto,
    val accountName: String? = null,
    val maskedAccountNo: String,
    val balance: Long? = null,
    val balanceAsOf: String? = null,
    val suggestedPrimary: Boolean = false,
)

@Serializable data class CardAssetDto(
    val assetId: String,
    val issuer: IssuerDto,
    val productName: String,
    val cardType: String = "CREDIT",        // CREDIT · CHECK
    val maskedNumber: String,
    val paymentDay: Int? = null,
    val hasPriorHistory: Boolean = false,
    val alreadyRegistered: Boolean = false,
)

@Serializable data class LinkCompleteRequest(
    val accountAssetIds: List<String>,
    val cardAssetIds: List<String>,
    val primaryAccountAssetId: String? = null,
)

@Serializable data class LinkCompleteResponse(
    val accounts: List<LinkedAccountDto> = emptyList(),
    val cards: List<LinkedCardDto> = emptyList(),
)

@Serializable data class LinkedAccountDto(val accountId: Long, val isPrimary: Boolean = false)

@Serializable data class LinkedCardDto(val userCardId: Long, val paymentDay: Int? = null)

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
)

/** #7 `GET /cards` */
@Serializable data class CardsResponse(val cards: List<CardDto> = emptyList(), val dataSyncedAt: String? = null)

@Serializable data class CardDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto,
    val productName: String,
    val cardType: String = "CREDIT",
    val maskedNumber: String,
    val performance: CardPerformanceDto? = null,
    val billingSummary: BillingSummaryDto? = null,
    val isDefault: Boolean = false,
)

@Serializable data class CardPerformanceDto(val yearMonth: String? = null, val targetAmount: Long? = null, val recognizedAmount: Long = 0)

@Serializable data class BillingSummaryDto(val paymentDay: Int? = null)

// ── 앱 안에서 쓰는 값 ──

object LinkPurpose {
    const val ONBOARDING = "ONBOARDING"
    const val CARD_ADD = "CARD_ADD"
}

enum class InstitutionType { BANK, CARD }

/** 카드사 · 은행 표시 — 짧은 이름 + 대표 색(서버 색, 없으면 회색) */
data class Brand(val name: String, val shortName: String, val colorArgb: Long?)

data class Institution(
    val id: String,
    val brand: Brand,
    val type: InstitutionType,
    val hasMyProducts: Boolean,
    val linkedCount: Int,
    val selectable: Boolean,
)

data class Terms(val code: String, val version: String, val title: String, val required: Boolean)

/** 1원을 보낼 내 명의 계좌 — 'KB국민은행 ·1234' */
data class OwnAccount(val required: Boolean, val bankName: String, val last4: String, val verified: Boolean) {
    val label: String get() = "$bankName ·$last4"
}

data class LinkSession(
    val id: String,
    val status: String,
    val institutionCount: Int,
    val ownAccount: OwnAccount?,
    val terms: List<Terms>,
)

data class OneWonSent(val prefix: String, val codeLength: Int, val expiresAtIso: String?)

data class AccountAsset(
    val assetId: String,
    val brand: Brand,
    val name: String,
    val last4: String,
    val suggestedPrimary: Boolean,
)

data class CardAsset(
    val assetId: String,
    val brand: Brand,
    val productName: String,
    val isCheck: Boolean,
    val last4: String,
    val paymentDay: Int?,
    val hasPriorHistory: Boolean,
    val alreadyRegistered: Boolean,
)

data class LinkAssets(val accounts: List<AccountAsset>, val cards: List<CardAsset>)

data class LinkCompletion(val accountIds: List<Long>, val userCardIds: List<Long>)

data class LinkedAccount(
    val accountId: Long,
    val brand: Brand,
    val name: String,
    val last4: String,
    val balance: Long?,
    val balanceAsOfIso: String?,
    val isPrimary: Boolean,
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
)

data class LinkedOverview(val accounts: List<LinkedAccount>, val cards: List<LinkedCard>, val syncedAtIso: String?) {
    val primaryAccount: LinkedAccount? get() = accounts.firstOrNull { it.isPrimary } ?: accounts.firstOrNull()
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

fun InstitutionDto.toInstitution() = Institution(
    id = institutionId,
    brand = brandOf(name, shortName, color),
    type = if (type.equals("BANK", ignoreCase = true)) InstitutionType.BANK else InstitutionType.CARD,
    hasMyProducts = hasMyProducts,
    linkedCount = linkedCount,
    selectable = selectable,
)

fun LinkSessionDto.toSession() = LinkSession(
    id = linkSessionId,
    status = status,
    institutionCount = institutionIds.size,
    ownAccount = ownAccountCheck?.let { check ->
        OwnAccount(
            required = check.required,
            bankName = check.bank?.name ?: "내 계좌",
            last4 = check.maskedAccountNo?.lastDigits().orEmpty(),
            verified = check.verifiedAt != null,
        )
    },
    terms = terms.map { Terms(it.termsCode, it.version, it.title, it.required) },
)

fun LinkAssetsResponse.toAssets() = LinkAssets(
    accounts = accounts.map {
        AccountAsset(it.assetId, brandOf(it.bank.name), it.accountName ?: "입출금", it.maskedAccountNo.lastDigits(), it.suggestedPrimary)
    },
    cards = cards.map {
        CardAsset(
            assetId = it.assetId,
            brand = brandOf(it.issuer.name, color = it.issuer.color),
            productName = it.productName,
            isCheck = it.cardType.equals("CHECK", ignoreCase = true),
            last4 = it.maskedNumber.lastDigits(),
            paymentDay = it.paymentDay,
            hasPriorHistory = it.hasPriorHistory,
            alreadyRegistered = it.alreadyRegistered,
        )
    },
)

fun AccountDto.toLinked() = LinkedAccount(
    accountId = accountId,
    brand = brandOf(bank.name),
    name = accountName ?: "입출금",
    last4 = maskedAccountNo.lastDigits(),
    balance = balance,
    balanceAsOfIso = balanceAsOf,
    isPrimary = isPrimary,
)

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
)

/** C-03 · C-12 주 버튼 — '계좌 1 · 카드 6 연결하기' */
fun linkButtonLabel(accounts: Int, cards: Int): String = when {
    accounts > 0 && cards > 0 -> "계좌 $accounts · 카드 $cards 연결하기"
    accounts > 0 -> "계좌 $accounts 연결하기"
    cards > 0 -> "카드 $cards 연결하기"
    else -> "연결할 것을 골라 주세요"
}
