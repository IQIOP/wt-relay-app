package com.wt.relay.data.remote

import com.wt.relay.core.SecureStorage
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class RegisterDeviceRequest(
    val device_id: String,
    val device_name: String,
    val app_version: String,
    val activation_code: String
)

data class RegisterDeviceResponse(
    val success: Boolean,
    val device_token: String?,
    val status: String?
)

data class IngestNotificationRequest(
    val event_id: String,
    val package_name: String,
    val sender_title: String,
    val message_text: String,
    val received_at: String
)

data class GenericResponse(val success: Boolean, val status: String?)

interface WtRelayApiService {
    @POST("devices/register")
    suspend fun registerDevice(@Body req: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @POST("devices/{id}/heartbeat")
    suspend fun sendHeartbeat(
        @Path("id") deviceId: String,
        @Header("X-Device-Token") deviceToken: String,
        @Body payload: Map<String, String>
    ): Response<GenericResponse>

    @POST("notifications/ingest")
    suspend fun ingestNotification(
        @Header("X-Device-Token") deviceToken: String,
        @Body req: IngestNotificationRequest
    ): Response<GenericResponse>

    companion object {
        fun create(storage: SecureStorage): WtRelayApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(storage.baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WtRelayApiService::class.java)
        }
    }
}
