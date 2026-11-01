package com.wt.relay.ui

import android.content.Intent
import android.os.Build
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
import com.wt.relay.data.remote.RegisterDeviceRequest
import com.wt.relay.data.remote.WtRelayApiService
import com.wt.relay.services.RelaySyncWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = SecureStorage(this)
        val dao = AppDatabase.get(this).notificationDao()

        setContent {
            var lang by remember { mutableStateOf(storage.language) }
            val isArabic = lang == "ar"
            val direction = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LiquidGlassScreen(
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
fun LiquidGlassScreen(
    storage: SecureStorage,
    dao: com.wt.relay.data.local.NotificationDao,
    isArabic: Boolean,
    onToggleLang: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by dao.observeRecentEvents().collectAsState(initial = emptyList())

    var apiUrl by remember { mutableStateOf(storage.baseUrl) }
    var activationCode by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }
    var isRegistered by remember { mutableStateOf(storage.deviceToken != null) }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0F1D), Color(0xFF131E36), Color(0xFF080B14))
    )

    Box(modifier = Modifier.fillMaxSize().background(bgGradient).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isArabic) "WT Relay — بوابة الترحيل الآمن" else "WT Relay — Secure Gateway",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = onToggleLang) {
                    Text(if (isArabic) "EN" else "عربي", color = Color(0xFF64B5F6))
                }
            }

            // Glass Card: Device Status & Pairing
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = (if (isArabic) "معرف الجهاز: " else "Device ID: ") + storage.deviceId,
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
                Text(
                    text = if (isRegistered)
                        (if (isArabic) "الحالة: مفعل ومتصل" else "Status: Active & Paired")
                    else
                        (if (isArabic) "الحالة: بانتظار التفعيل" else "Status: Pending Activation"),
                    color = if (isRegistered) Color(0xFF81C784) else Color(0xFFFFB74D),
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text(if (isArabic) "رابط خادم Cloudflare Worker" else "Cloudflare Worker URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (!isRegistered) {
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = { activationCode = it },
                        label = { Text(if (isArabic) "رمز التفعيل" else "Activation Code") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    storage.baseUrl = apiUrl.trim()
                                    val api = WtRelayApiService.create(storage)
                                    val resp = api.registerDevice(
                                        RegisterDeviceRequest(
                                            device_id = storage.deviceId,
                                            device_name = Build.MODEL ?: "Android Device",
                                            app_version = "1.0.0",
                                            activation_code = activationCode.trim()
                                        )
                                    )
                                    if (resp.isSuccessful && resp.body()?.device_token != null) {
                                        storage.deviceToken = resp.body()!!.device_token
                                        isRegistered = true
                                        statusMsg = if (isArabic) "تم تفعيل الجهاز بنجاح" else "Device registered successfully"
                                        RelaySyncWorker.enqueueImmediateSync(context)
                                    } else {
                                        statusMsg = if (isArabic) "فشل التفعيل: تحقق من الرمز" else "Activation failed: Check code"
                                    }
                                } catch (e: Exception) {
                                    statusMsg = e.localizedMessage ?: "Network Error"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isArabic) "تفعيل وربط الجهاز" else "Activate & Pair Device")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "صلاحية الإشعارات" else "Listener Access")
                    }
                    OutlinedButton(
                        onClick = { RelaySyncWorker.enqueueImmediateSync(context) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "مزامنة الآن" else "Sync Queue")
                    }
                }

                if (statusMsg.isNotEmpty()) {
                    Text(statusMsg, color = Color(0xFF4FC3F7), fontSize = 13.sp)
                }
            }

            // Glass Card: Offline Queue & Delivery Feed
            Text(
                text = if (isArabic) "سجل طابور الإشعارات (${events.size})" else "Notification Queue (${events.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.senderTitle, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(ev.messageText, color = Color(0xFFB0BEC5), fontSize = 13.sp, maxLines = 2)
                        }
                        Text(
                            text = ev.status,
                            color = when (ev.status) {
                                "DELIVERED" -> Color(0xFF81C784)
                                "PENDING" -> Color(0xFFFFD54F)
                                else -> Color(0xFFE57373)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
