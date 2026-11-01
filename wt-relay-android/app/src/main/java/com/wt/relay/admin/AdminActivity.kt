package com.wt.relay.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isArabic by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalLayoutDirection provides if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    AdminDashboardRoot(
                        isArabic = isArabic,
                        onToggleLang = { isArabic = !isArabic }
                    )
                }
            }
        }
    }
}

@Composable
fun AdminDashboardRoot(isArabic: Boolean, onToggleLang: () -> Unit) {
    val baseUrl = "https://wt-relay-backend.7i8l501n4h.workers.dev"
    var token by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(0) } // 0: Metrics, 1: Devices, 2: Supervisors, 3: Logs

    val bg = Brush.verticalGradient(listOf(Color(0xFF090D16), Color(0xFF101726), Color(0xFF06090F)))

    Box(modifier = Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        if (token == null) {
            AdminLoginScreen(baseUrl, isArabic) { token = it }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isArabic) "WT Relay — لوحة التحكم" else "WT Relay — Admin Panel",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleLang) {
                            Text(if (isArabic) "English" else "عربي", color = Color(0xFF64B5F6))
                        }
                        Button(onClick = { token = null }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) {
                            Text(if (isArabic) "خروج" else "Logout")
                        }
                    }
                }

                // Tab Bar
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tabs = if (isArabic) listOf("الرئيسية", "الأجهزة", "المشرفين", "السجلات") else listOf("Metrics", "Devices", "Supervisors", "Logs")
                    tabs.forEachIndexed { idx, name ->
                        FilterChip(
                            selected = currentTab == idx,
                            onClick = { currentTab = idx },
                            label = { Text(name, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Main Views
                when (currentTab) {
                    0 -> MetricsView(baseUrl, token!!, isArabic)
                    1 -> DevicesView(baseUrl, token!!, isArabic)
                    2 -> SupervisorsView(baseUrl, token!!, isArabic)
                    3 -> LogsView(baseUrl, token!!, isArabic)
                }
            }
        }
    }
}

@Composable
fun AdminLoginScreen(baseUrl: String, isArabic: Boolean, onLoggedIn: (String) -> Unit) {
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("admin123") }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (isArabic) "تسجيل الدخول للمسؤول" else "Admin Authorization", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(if (isArabic) "اسم المستخدم" else "Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(if (isArabic) "كلمة المرور" else "Password") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val client = OkHttpClient()
                            val body = JSONObject().put("username", user.trim()).put("password", pass.trim())
                            val req = Request.Builder().url("$baseUrl/auth/login").post(body.toString().toRequestBody("application/json".toMediaType())).build()
                            val resp = client.newCall(req).execute()
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            withContext(Dispatchers.Main) {
                                if (resp.isSuccessful && json.optBoolean("success")) {
                                    onLoggedIn(json.getString("token"))
                                } else {
                                    errorMsg = json.optString("error", "Login failed")
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { errorMsg = e.localizedMessage ?: "Network error" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isArabic) "دخول" else "Login")
            }
            if (errorMsg.isNotEmpty()) Text(errorMsg, color = Color(0xFFEF5350), fontSize = 13.sp)
        }
    }
}

@Composable
fun MetricsView(baseUrl: String, token: String, isArabic: Boolean) {
    var metrics by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url("$baseUrl/admin/dashboard").addHeader("Authorization", "Bearer $token").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = JSONObject(res.body?.string() ?: "{}")
                withContext(Dispatchers.Main) { metrics = json.optJSONObject("metrics") }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { load() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isArabic) "تحديث الإحصائيات الحية" else "Refresh Live Metrics")
        }
        metrics?.let { m ->
            MetricItem(if (isArabic) "الأجهزة المتصلة" else "Active Devices", "${m.optInt("active_devices")} / ${m.optInt("total_devices")}", Color(0xFF81C784))
            MetricItem(if (isArabic) "إجمالي الإشعارات" else "Total Notifications", "${m.optInt("total_notifications")}", Color(0xFF64B5F6))
            MetricItem(if (isArabic) "تسليم ناجح (Telegram)" else "Delivered to Telegram", "${m.optInt("delivered_notifications")}", Color(0xFF81C784))
            MetricItem(if (isArabic) "تسليم فاشل" else "Failed Deliveries", "${m.optInt("failed_notifications")}", Color(0xFFE57373))
            MetricItem(if (isArabic) "آخر اتصال نبض" else "Last Heartbeat", m.optString("last_heartbeat", "-"), Color(0xFFFFD54F))
        } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun MetricItem(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DevicesView(baseUrl: String, token: String, isArabic: Boolean) {
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url("$baseUrl/admin/devices").addHeader("Authorization", "Bearer $token").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val arr = JSONObject(res.body?.string() ?: "{}").optJSONArray("devices") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                withContext(Dispatchers.Main) { devices = list }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices) { d ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(d.optString("device_name"), color = Color.White, fontWeight = FontWeight.Bold)
                Text("ID: " + d.optString("device_id"), color = Color(0xFF90A4AE), fontSize = 11.sp)
                Text("${if (isArabic) "المشرف: " else "Supervisor: "}${d.optString("supervisor_name")}", color = Color.LightGray, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.optString("status"), color = if (d.optString("status") == "ACTIVE") Color(0xFF81C784) else Color(0xFFE57373), fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val client = OkHttpClient()
                                val nextStatus = if (d.optString("status") == "ACTIVE") "SUSPENDED" else "ACTIVE"
                                val body = JSONObject().put("status", nextStatus).toString().toRequestBody("application/json".toMediaType())
                                client.newCall(Request.Builder().url("$baseUrl/admin/devices/${d.optString("device_id")}/status").addHeader("Authorization", "Bearer $token").post(body).build()).execute()
                                load()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text(if (d.optString("status") == "ACTIVE") (if (isArabic) "تعطيل" else "Disable") else (if (isArabic) "تفعيل" else "Enable"), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SupervisorsView(baseUrl: String, token: String, isArabic: Boolean) {
    var supervisors by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var botToken by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var resultMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url("$baseUrl/admin/supervisors").addHeader("Authorization", "Bearer $token").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val arr = JSONObject(res.body?.string() ?: "{}").optJSONArray("supervisors") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                withContext(Dispatchers.Main) { supervisors = list }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { showAdd = !showAdd }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isArabic) "+ إضافة مشرف جديد" else "+ Add New Supervisor")
        }

        if (showAdd) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(if (isArabic) "اسم المشرف" else "Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = botToken, onValueChange = { botToken = it }, label = { Text("Bot Token") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = chatId, onValueChange = { chatId = it }, label = { Text("Chat / Channel ID") }, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val client = OkHttpClient()
                                val body = JSONObject().put("bot_token", botToken).put("chat_id", chatId).toString().toRequestBody("application/json".toMediaType())
                                val res = client.newCall(Request.Builder().url("$baseUrl/admin/telegram/test").addHeader("Authorization", "Bearer $token").post(body).build()).execute()
                                withContext(Dispatchers.Main) {
                                    resultMsg = if (res.isSuccessful) "✓ الاتصال بتليجرام يعمل بنجاح" else "✕ فشل الاتصال بتليجرام"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "فحص Telegram" else "Test Telegram")
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val client = OkHttpClient()
                                val body = JSONObject().put("name", name).put("bot_token", botToken).put("chat_id", chatId).put("max_devices", 5).toString().toRequestBody("application/json".toMediaType())
                                val res = client.newCall(Request.Builder().url("$baseUrl/admin/supervisors").addHeader("Authorization", "Bearer $token").post(body).build()).execute()
                                val json = JSONObject(res.body?.string() ?: "{}")
                                withContext(Dispatchers.Main) {
                                    if (res.isSuccessful) {
                                        resultMsg = "كود التفعيل: " + json.optString("activation_code")
                                        showAdd = false
                                        load()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "حفظ وتوليد الكود" else "Save & Generate Code")
                    }
                }
                if (resultMsg.isNotEmpty()) Text(resultMsg, color = Color(0xFF64B5F6), fontSize = 12.sp)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(supervisors) { s ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(s.optString("name"), color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Token: " + s.optString("masked_token"), color = Color(0xFF90A4AE), fontSize = 12.sp)
                    Text("Destination: " + s.optString("chat_id"), color = Color(0xFF90A4AE), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun LogsView(baseUrl: String, token: String, isArabic: Boolean) {
    var logs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url("$baseUrl/admin/audit-logs").addHeader("Authorization", "Bearer $token").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val arr = JSONObject(res.body?.string() ?: "{}").optJSONArray("audit_logs") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                withContext(Dispatchers.Main) { logs = list }
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(logs) { log ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(log.optString("action"), color = Color(0xFF64B5F6), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(log.optString("created_at"), color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}
