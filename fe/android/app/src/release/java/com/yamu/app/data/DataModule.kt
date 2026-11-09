package com.yamu.app.data

import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthRepositoryImpl
import com.yamu.app.feature.auth.data.SignupRepository
import com.yamu.app.feature.auth.data.SignupRepositoryImpl
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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** release 빌드: 진짜 Repository만(가짜 코드는 src/debug에만 있다) */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun homeRepository(impl: HomeRepositoryImpl): HomeRepository

    @Binds
    abstract fun authRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun signupRepository(impl: SignupRepositoryImpl): SignupRepository

    @Binds
    abstract fun linkRepository(impl: LinkRepositoryImpl): LinkRepository

    @Binds
    abstract fun cashflowRepository(impl: CashflowRepositoryImpl): CashflowRepository

    @Binds
    abstract fun paymentRepository(impl: PaymentRepositoryImpl): PaymentRepository

    @Binds
    abstract fun performanceRepository(impl: PerformanceRepositoryImpl): PerformanceRepository

    @Binds
    abstract fun forecastRepository(impl: ForecastRepositoryImpl): ForecastRepository

    @Binds
    abstract fun reportRepository(impl: ReportRepositoryImpl): ReportRepository

    @Binds
    abstract fun myRepository(impl: MyRepositoryImpl): MyRepository
}
