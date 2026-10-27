package com.yamu.app.core.network

import com.yamu.app.BuildConfig
import com.yamu.app.core.security.KeystoreTokenStore
import com.yamu.app.core.security.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** 로그는 debug에서 요청 줄만(BASIC) — 본문에 토큰이 있을 수 있어 BODY는 쓰지 않는다 */
    private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(YamuHeaders.AUTHORIZATION)
                        redactHeader(YamuHeaders.STEP_UP_TOKEN)
                    },
                )
            }
        }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(YamuJson.asConverterFactory("application/json; charset=utf-8".toMediaType()))
        .build()

    /** 토큰 재발급 전용 — 인증 헤더 · 재발급 없이 */
    @Provides
    @Singleton
    @Named("auth")
    fun authRetrofit(): Retrofit = retrofit(baseClient().build())

    @Provides
    @Singleton
    fun authApi(@Named("auth") retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun okHttpClient(tokenStore: TokenStore, authApi: AuthApi): OkHttpClient = baseClient()
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(TokenAuthenticator(tokenStore, authApi))
        .build()

    /** 일반 API용 — 기능마다 `retrofit.create(XxxApi::class.java)` */
    @Provides
    @Singleton
    fun apiRetrofit(client: OkHttpClient): Retrofit = retrofit(client)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    abstract fun tokenStore(impl: KeystoreTokenStore): TokenStore
}
