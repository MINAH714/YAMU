package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.*
import com.yamu.app.feature.link.data.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject

data class SalaryData(val income: SettingsIncomeDto?, val accounts: List<IncomeAccount>, val next: CashflowEntry?)

interface SalaryRepository {
    suspend fun load(today: LocalDate): ApiResult<SalaryData>
    suspend fun create(body: CashflowCreateRequest, key: String): ApiResult<Long>
    suspend fun designate(id: Long): ApiResult<Unit>
    suspend fun update(id: Long, body: CashflowUpdateRequest): ApiResult<Unit>
}

class SalaryRepositoryImpl @Inject constructor(
    private val settings: CashflowApi,
    private val forecast: ForecastApi,
    private val cashflow: CashflowRepository,
) : SalaryRepository {
    override suspend fun load(today: LocalDate): ApiResult<SalaryData> {
        val saved = when (val r = apiCall { settings.settings() }) {
            is ApiResult.Success -> r.data.income
            is ApiResult.Failure -> return r
        }
        val accounts = when (val r = cashflow.incomeAccounts()) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return r
        }
        val next = if (saved == null) null else when (val r = apiCall {
            forecast.cashflowItems(today.plusDays(1).toString(), today.plusDays(62).toString())
        }) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> r.data.items.mapNotNull { it.toEntry() }
                .filter { it.itemId == saved.cashflowItemId && it.isIncome && it.repeat == CashflowRepeat.MONTHLY && it.date.isAfter(today) && it.occurrenceDate.isAfter(today) }
                .minByOrNull { it.occurrenceDate }
        }
        return ApiResult.Success(SalaryData(saved, accounts, next))
    }

    override suspend fun create(body: CashflowCreateRequest, key: String) = cashflow.createIncome(body, key)
    override suspend fun designate(id: Long) = apiCallNoData {
        settings.patchSettings(buildJsonObject { put("incomeCashflowItemId", id); put("horizon", "NEXT_INCOME") })
    }
    override suspend fun update(id: Long, body: CashflowUpdateRequest) = apiCallNoData { forecast.updateItem(id, body) }
}

/** 원래 다음 회차부터 분기한다. 지급일을 바꾸지 않으면 반복 기준일·종료일을 보존한다. 이후 개별 회차 변경은 서버 정책대로 덮는다. */
internal fun salaryPatch(entry: CashflowEntry, input: IncomeInput, today: LocalDate): CashflowUpdateRequest {
    val changedDay = input.day != entry.dayOfMonth
    val date = if (changedDay) nextMonthlyDate(input.day, today) else null
    return CashflowUpdateRequest(
        amount = input.amount,
        accountId = input.accountId,
        occurrenceDate = entry.occurrenceDate.toString(),
        scope = CashflowScope.THIS_AND_FUTURE.name,
        date = date?.toString(),
        recurrence = if (changedDay) RecurrenceRequest("MONTHLY", dayOfMonth = input.day, endDate = entry.endDate?.toString()) else null,
    )
}
