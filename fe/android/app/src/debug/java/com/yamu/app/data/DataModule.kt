package com.yamu.app.data

import com.yamu.app.core.network.ApiFeature
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthRepositoryImpl
import com.yamu.app.feature.auth.data.SignupRepository
import com.yamu.app.feature.auth.data.SignupRepositoryImpl
import com.yamu.app.feature.billing.data.BillingRepository
import com.yamu.app.feature.billing.data.BillingRepositoryImpl
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastRepositoryImpl
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeRepositoryImpl
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.link.data.CashflowRepositoryImpl
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkRepositoryImpl
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.MyRepositoryImpl
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRepositoryImpl
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.PerformanceRepositoryImpl
import com.yamu.app.feature.report.data.AssetFlowRepository
import com.yamu.app.feature.report.data.AssetFlowRepositoryImpl
import com.yamu.app.feature.report.data.ReportRepository
import com.yamu.app.feature.report.data.ReportRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * debug 빌드: local.properties의 yamu.realApi에 적은 기능만 진짜, 나머지는 가짜 Repository(ApiFeature).
 * release는 src/release의 DataModule — 진짜만.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun homeRepository(real: Provider<HomeRepositoryImpl>, fake: Provider<FakeHomeRepository>): HomeRepository =
        if (ApiFeature.HOME.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun authRepository(real: Provider<AuthRepositoryImpl>, fake: Provider<FakeAuthRepository>): AuthRepository =
        if (ApiFeature.AUTH.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun signupRepository(real: Provider<SignupRepositoryImpl>, fake: Provider<FakeSignupRepository>): SignupRepository =
        if (ApiFeature.AUTH.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun linkRepository(real: Provider<LinkRepositoryImpl>, fake: Provider<FakeLinkRepository>): LinkRepository =
        if (ApiFeature.LINK.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun cashflowRepository(real: Provider<CashflowRepositoryImpl>, fake: Provider<FakeCashflowRepository>): CashflowRepository =
        if (ApiFeature.CASHFLOW.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun paymentRepository(real: Provider<RealPaymentWithFakes>, fake: Provider<FakePaymentRepository>): PaymentRepository =
        if (ApiFeature.PAYMENT.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun performanceRepository(real: Provider<PerformanceRepositoryImpl>, fake: Provider<FakePerformanceRepository>): PerformanceRepository =
        if (ApiFeature.PERFORMANCE.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun forecastRepository(
        real: Provider<ForecastRepositoryImpl>,
        cashflowOnly: Provider<RealCashflowWithFakes>,
        fake: Provider<FakeForecastRepository>,
    ): ForecastRepository = when {
        ApiFeature.FORECAST.isReal -> real.get()
        ApiFeature.CASHFLOW.isReal -> cashflowOnly.get()
        else -> fake.get()
    }

    @Provides
    @Singleton
    fun reportRepository(real: Provider<ReportRepositoryImpl>, fake: Provider<FakeReportRepository>): ReportRepository =
        if (ApiFeature.REPORT.isReal) real.get() else fake.get()

    /** 소비분석 자산 흐름 — 잔고예보와 같은 예보 API라 잔고예보(FORECAST)가 진짜일 때만 진짜 */
    @Provides
    @Singleton
    fun assetFlowRepository(real: Provider<AssetFlowRepositoryImpl>, fake: Provider<FakeAssetFlowRepository>): AssetFlowRepository =
        if (ApiFeature.FORECAST.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun myRepository(real: Provider<MyRepositoryImpl>, fake: Provider<FakeMyRepository>): MyRepository =
        if (ApiFeature.MY.isReal) real.get() else fake.get()

    @Provides
    @Singleton
    fun billingRepository(real: Provider<BillingRepositoryImpl>, fake: Provider<FakeBillingRepository>): BillingRepository =
        if (ApiFeature.BILLING.isReal) real.get() else fake.get()
}
