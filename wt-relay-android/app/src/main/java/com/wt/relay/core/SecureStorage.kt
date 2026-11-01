package com.wt.relay.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SecureStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wt_relay_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var baseUrl: String
        get() = prefs.getString("base_url", "https://wt-relay-backend.workers.dev/") ?: "https://wt-relay-backend.workers.dev/"
        set(value) = prefs.edit().putString("base_url", if (value.endsWith("/")) value else "$value/").apply()

    val deviceId: String
        get() {
            var existing = prefs.getString("device_id", null)
            if (existing == null) {
                existing = "DVC-" + UUID.randomUUID().toString().uppercase()
                prefs.edit().putString("device_id", existing).apply()
            }
            return existing
        }

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        set(value) = prefs.edit().putString("device_token", value).apply()

    var language: String
        get() = prefs.getString("app_lang", "ar") ?: "ar"
        set(value) = prefs.edit().putString("app_lang", value).apply()

    var lastSyncTime: String
        get() = prefs.getString("last_sync", "-") ?: "-"
        set(value) = prefs.edit().putString("last_sync", value).apply()
}
