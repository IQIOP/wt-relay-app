package com.wt.relay.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wt.relay.core.SecureStorage
import com.wt.relay.data.local.AppDatabase
import com.wt.relay.services.RelaySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = SecureStorage(this)
        val dao = AppDatabase.get(this).notificationDao()

        setContent {
            var lang by remember { mutableStateOf(storage.language) }
            val isArabic = lang == "ar"

            CompositionLocalProvider(LocalLayoutDirection provides if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    UserRelayScreen(
                        storage = storage,
                        dao = dao,
                        isArabic = isArabic,
                        onToggleLang = {
                            lang = if (isArabic) "en" else "ar"
                            storage.language = lang
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UserRelayScreen(
    storage: SecureStorage,
    dao: com.wt.relay.data.local.NotificationDao,
    isArabic: Boolean,
    onToggleLang: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by dao.observeRecentEvents().collectAsState(initial = emptyList())

    var botTokenInput by remember { mutableStateOf("") }
    var destinationInput by remember { mutableStateOf("") }
    var statusFeedback by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0D131F), Color(0xFF131D31), Color(0xFF090D15))
    )

    Box(modifier = Modifier.fillMaxSize().background(bgGradient).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isArabic) "WT Relay — ترحيل الإشعارات" else "WT Relay — User Client",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = onToggleLang) {
                    Text(if (isArabic) "English" else "عربي", color = Color(0xFF64B5F6))
                }
            }

            // Status Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${if (isArabic) "معرف الجهاز: " else "Device ID: "}${storage.deviceId.take(18)}...",
                    color = Color(0xFF90A4AE),
                    fontSize = 12.sp
                )
                Text(
                    text = "${if (isArabic) "آخر مزامنة: " else "Last Sync: "}${storage.lastSyncTime}",
                    color = Color(0xFFCFD8DC),
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            RelaySyncWorker.enqueueImmediateSync(context)
                            statusFeedback = if (isArabic) "تم تشغيل المزامنة الفورية..." else "Sync triggered..."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "مزامنة الآن" else "Sync Now")
                    }

                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "صلاحية الإشعارات" else "Permissions")
                    }
                }
            }

            // Telegram Setup Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isArabic) "إعدادات وجهة Telegram" else "Telegram Destination Setup",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = { botTokenInput = it },
                    label = { Text(if (isArabic) "Telegram Bot Token" else "Bot Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = destinationInput,
                    onValueChange = { destinationInput = it },
                    label = { Text(if (isArabic) "معرف الوجهة (Chat / Channel / Group ID)" else "Destination Chat/Channel ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isOperating = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val client = OkHttpClient()
                                    val json = JSONObject().apply {
                                        put("bot_token", botTokenInput.trim())
                                        put("chat_id", destinationInput.trim())
                                    }
                                    val req = Request.Builder()
                                        .url(storage.baseUrl.trimEnd('/') + "/api/user/test-telegram")
                                        .addHeader("X-Device-Token", storage.deviceToken ?: "")
                                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                                        .build()

                                    val resp = client.newCall(req).execute()
                                    val respBody = resp.body?.string() ?: ""
                                    withContext(Dispatchers.Main) {
                                        if (resp.isSuccessful) {
                                            statusFeedback = if (isArabic) "✓ نجح الاتصال وتم إرسال رسالة الاختبار بنجاح!" else "✓ Telegram Connected Successfully!"
                                        } else {
                                            val err = JSONObject(respBody).optString("error", "Failed")
                                            statusFeedback = "✕ $err"
                                        }
                                        isOperating = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        statusFeedback = "✕ Error: ${e.localizedMessage}"
                                        isOperating = false
                                    }
                                }
                            }
                        },
                        enabled = !isOperating && storage.deviceToken != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "اختبار الاتصال" else "Test Telegram")
                    }

                    OutlinedButton(
                        onClick = {
                            isOperating = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val client = OkHttpClient()
                                    val json = JSONObject().apply {
                                        put("bot_token", botTokenInput.trim())
                                        put("chat_id", destinationInput.trim())
                                    }
                                    val req = Request.Builder()
                                        .url(storage.baseUrl.trimEnd('/') + "/api/user/update-telegram")
                                        .addHeader("X-Device-Token", storage.deviceToken ?: "")
                                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                                        .build()

                                    val resp = client.newCall(req).execute()
                                    val respStr = resp.body?.string() ?: ""
                                    withContext(Dispatchers.Main) {
                                        if (resp.isSuccessful) {
                                            val jsonRes = JSONObject(respStr)
                                            botTokenInput = jsonRes.optString("masked_token", "")
                                            statusFeedback = if (isArabic) "✓ تم حفظ الإعدادات وتأكيد الاتصال." else "✓ Settings Saved & Verified."
                                        } else {
                                            val err = JSONObject(respStr).optString("error", "Error")
                                            statusFeedback = "✕ $err"
                                        }
                                        isOperating = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        statusFeedback = "✕ Error: ${e.localizedMessage}"
                                        isOperating = false
                                    }
                                }
                            }
                        },
                        enabled = !isOperating && storage.deviceToken != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "حفظ الوجهة" else "Save")
                    }
                }

                if (statusFeedback.isNotEmpty()) {
                    Text(
                        text = statusFeedback,
                        color = if (statusFeedback.startsWith("✓")) Color(0xFF81C784) else Color(0xFFEF5350),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Notification Delivery Queue Feed
            Text(
                text = "${if (isArabic) "حالة إرسال الإشعارات" else "Relay Pipeline Events"} (${events.size})",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.senderTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(ev.messageText, color = Color(0xFFB0BEC5), fontSize = 12.sp, maxLines = 1)
                        }

                        val (statusBadge, statusColor) = when (ev.status) {
                            "DELIVERED" -> Pair("✓ Sent", Color(0xFF81C784))
                            "PENDING" -> Pair("⏳ Pending", Color(0xFFFFD54F))
                            "RETRYING" -> Pair("↻ Retrying", Color(0xFF64B5F6))
                            else -> Pair("✕ Failed", Color(0xFFE57373))
                        }

                        Text(
                            text = statusBadge,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
