package com.yamu.app.data

import com.yamu.app.BuildConfig
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthRepositoryImpl
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
import com.yamu.app.feature.report.data.ReportRepository
import com.yamu.app.feature.report.data.ReportRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * debug 빌드: USE_FAKE_API면 가짜, 아니면 진짜 Repository.
 * API가 준비되면 기능마다 여기서 진짜로 바꾼다(release는 src/release의 DataModule — 진짜만).
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun homeRepository(real: Provider<HomeRepositoryImpl>, fake: Provider<FakeHomeRepository>): HomeRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun authRepository(real: Provider<AuthRepositoryImpl>, fake: Provider<FakeAuthRepository>): AuthRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun linkRepository(real: Provider<LinkRepositoryImpl>, fake: Provider<FakeLinkRepository>): LinkRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun cashflowRepository(real: Provider<CashflowRepositoryImpl>, fake: Provider<FakeCashflowRepository>): CashflowRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun paymentRepository(real: Provider<PaymentRepositoryImpl>, fake: Provider<FakePaymentRepository>): PaymentRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun performanceRepository(real: Provider<PerformanceRepositoryImpl>, fake: Provider<FakePerformanceRepository>): PerformanceRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun forecastRepository(real: Provider<ForecastRepositoryImpl>, fake: Provider<FakeForecastRepository>): ForecastRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun reportRepository(real: Provider<ReportRepositoryImpl>, fake: Provider<FakeReportRepository>): ReportRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()

    @Provides
    @Singleton
    fun myRepository(real: Provider<MyRepositoryImpl>, fake: Provider<FakeMyRepository>): MyRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()
}
