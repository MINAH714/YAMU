package com.yamu.app.feature.my.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.core.security.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/** 마이 · 알림 — #23 · #38/#39 · #52/#53 · #41 · #42/#16 · #49/#50/#51. 13 문서 9·10-3 */
interface MyApi {
    @GET("users/{userId}")
    suspend fun me(@Path("userId") userId: String): Response<ApiEnvelope<UserInfoDto>>

    @PATCH("users/{userId}/payment-settings")
    suspend fun paymentSettings(@Path("userId") userId: String, @Body body: PaymentSettingsPatch): Response<ApiEnvelope<JsonElement>>

    @PATCH("users/me/security-settings")
    suspend fun securitySettings(@Body body: SecurityPatch): Response<ApiEnvelope<JsonElement>>

    @GET("notifications/settings")
    suspend fun notificationSettings(): Response<ApiEnvelope<NotificationSettingsDto>>

    @PATCH("notifications/settings")
    suspend fun patchNotificationSettings(@Body body: JsonObject): Response<ApiEnvelope<JsonElement>>

    @GET("auth/devices")
    suspend fun devices(): Response<ApiEnvelope<DevicesDto>>

    @DELETE("auth/devices/{id}")
    suspend fun revokeDevice(@Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String, @Path("id") userDeviceId: Long): Response<ApiEnvelope<JsonElement>>

    @GET("notifications")
    suspend fun notifications(@Query("page") page: Int, @Query("size") size: Int = 20): Response<ApiEnvelope<NotificationsDto>>

    @PATCH("notifications/{id}/read")
    suspend fun read(@Path("id") notificationId: Long): Response<ApiEnvelope<JsonElement>>

    @PATCH("notifications/read-all")
    suspend fun readAll(): Response<ApiEnvelope<JsonElement>>
}

interface MyRepository {
    /** M-01 · M-06 · M-08 — 자동 잠금 시간은 기기에도 저장(앱을 다시 켜도 같은 값) */
    suspend fun me(): ApiResult<MyInfo>
    suspend fun setAutoSelect(on: Boolean): ApiResult<Unit>
    suspend fun setAutoLock(seconds: Int): ApiResult<Unit>
    suspend fun setPinOnlyForCode(pinOnly: Boolean): ApiResult<Unit>
    suspend fun notificationSettings(): ApiResult<NotiSettings>
    suspend fun setNotification(kind: NotiKind, enabled: Boolean): ApiResult<Unit>
    suspend fun setNotificationTime(hhmm: String): ApiResult<Unit>
    suspend fun devices(): ApiResult<List<Device>>
    /** 다른 기기 로그아웃 — 본인 인증 토큰(APP_UNLOCK) 필요 */
    suspend fun revokeDevice(id: Long, stepUpToken: String): ApiResult<Unit>
    suspend fun notifications(page: Int): ApiResult<NotificationsPage>
    suspend fun markRead(id: Long): ApiResult<Unit>
    suspend fun markAllRead(): ApiResult<Unit>
}

@Singleton
class MyRepositoryImpl @Inject constructor(private val api: MyApi, private val session: SessionStore) : MyRepository {

    private suspend fun userId() = session.read().userId?.toString() ?: "me"

    override suspend fun me(): ApiResult<MyInfo> =
        apiCall { api.me(userId()) }.map { it.toMyInfo() }.also { r ->
            if (r is ApiResult.Success) session.setAutoLockSeconds(r.data.autoLockSeconds)
        }

    override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> = apiCallNoData { api.paymentSettings(userId(), PaymentSettingsPatch(on)) }

    override suspend fun setAutoLock(seconds: Int): ApiResult<Unit> =
        apiCallNoData { api.securitySettings(SecurityPatch(autoLockSeconds = seconds)) }.also {
            if (it is ApiResult.Success) session.setAutoLockSeconds(seconds)
        }

    override suspend fun setPinOnlyForCode(pinOnly: Boolean): ApiResult<Unit> =
        apiCallNoData { api.securitySettings(SecurityPatch(barcodeAuthMethod = if (pinOnly) "PIN_ONLY" else "BIOMETRIC_OR_PIN")) }

    override suspend fun notificationSettings(): ApiResult<NotiSettings> = apiCall { api.notificationSettings() }.map { it.toSettings() }

    override suspend fun setNotification(kind: NotiKind, enabled: Boolean): ApiResult<Unit> =
        apiCallNoData { api.patchNotificationSettings(notificationToggleBody(kind, enabled)) }

    override suspend fun setNotificationTime(hhmm: String): ApiResult<Unit> =
        apiCallNoData { api.patchNotificationSettings(notificationTimeBody(hhmm)) }

    override suspend fun devices(): ApiResult<List<Device>> = apiCall { api.devices() }.map { r ->
        r.items.filter { !it.status.equals("REVOKED", ignoreCase = true) }
            .map { Device(it.userDeviceId, it.deviceName ?: it.platform ?: "기기", it.isCurrent, it.lastUsedAt) }
            .sortedByDescending { it.current }
    }

    override suspend fun revokeDevice(id: Long, stepUpToken: String): ApiResult<Unit> = apiCallNoData { api.revokeDevice(stepUpToken, id) }

    override suspend fun notifications(page: Int): ApiResult<NotificationsPage> =
        apiCall { api.notifications(page) }.map { r -> NotificationsPage(r.unreadCount, r.items.map { it.toNotification() }, r.hasNext) }

    override suspend fun markRead(id: Long): ApiResult<Unit> = apiCallNoData { api.read(id) }

    override suspend fun markAllRead(): ApiResult<Unit> = apiCallNoData { api.readAll() }
}

@Module
@InstallIn(SingletonComponent::class)
object MyApiModule {
    @Provides
    @Singleton
    fun myApi(retrofit: Retrofit): MyApi = retrofit.create(MyApi::class.java)
}
