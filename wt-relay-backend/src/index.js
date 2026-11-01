function escapeHtml(text) {
  if (!text) return "";
  return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

async function sha256Hex(input) {
  const data = new TextEncoder().encode(input);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hashBuffer)).map(b => b.toString(16).padStart(2, "0")).join("");
}

async function sendTelegramDirect(botToken, chatId, messageHtml) {
  const url = `https://api.telegram.org/bot${botToken}/sendMessage`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: String(chatId).trim(),
        text: messageHtml,
        parse_mode: "HTML",
        disable_web_page_preview: true
      })
    });
    const data = await res.json().catch(() => ({}));
    return {
      httpStatus: res.status,
      ok: res.ok && data.ok === true,
      data: data,
      description: data.description || (res.ok ? "Success" : "Unknown error")
    };
  } catch (err) {
    return { httpStatus: 0, ok: false, description: `Network Error: ${err.message}` };
  }
}

export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Device-Token"
    };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const method = request.method;

    const sendJson = (body, status = 200) =>
      new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

    // Authentication Helper (Strict Session Verification)
    async function getAdminUser() {
      const authHeader = request.headers.get("Authorization") || "";
      if (!authHeader.startsWith("Bearer ")) return null;
      const rawToken = authHeader.slice(7).trim();
      const tokenHash = await sha256Hex(rawToken);

      return await env.DB.prepare(`
        SELECT s.user_id, u.username, r.name as role_name, r.permissions
        FROM user_sessions s
        JOIN users u ON s.user_id = u.id
        JOIN roles r ON u.role_id = r.id
        WHERE s.token_hash = ? AND s.expires_at > datetime('now') AND u.status = 'ACTIVE'
      `).bind(tokenHash).first();
    }

    // 1. Health
    if (path === "/health" && method === "GET") {
      const dbCheck = await env.DB.prepare("SELECT 1 as ok").first();
      return sendJson({ status: "ONLINE", database: dbCheck?.ok === 1 ? "CONNECTED" : "ERROR" });
    }

    // 2. Authentication: Login
    if (path === "/auth/login" && method === "POST") {
      const { username, password } = await request.json().catch(() => ({}));
      if (!username || !password) return sendJson({ success: false, error: "Missing credentials" }, 400);

      const user = await env.DB.prepare(`
        SELECT u.*, r.name as role_name 
        FROM users u 
        JOIN roles r ON u.role_id = r.id 
        WHERE u.username = ? AND u.status = 'ACTIVE'
      `).bind(username.trim()).first();

      const passHash = await sha256Hex(password);
      if (!user || user.password_hash !== passHash) {
        return sendJson({ success: false, error: "Invalid username or password" }, 401);
      }

      const rawToken = crypto.randomUUID() + "-" + crypto.randomUUID();
      const tokenHash = await sha256Hex(rawToken);
      const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

      await env.DB.prepare(`
        INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)
      `).bind(tokenHash, user.id, expiresAt).run();

      await env.DB.prepare(`
        INSERT INTO audit_logs (actor_id, actor_type, action, target_resource, metadata)
        VALUES (?, 'USER', 'LOGIN', 'auth/login', ?)
      `).bind(user.id, JSON.stringify({ username: user.username })).run();

      return sendJson({
        success: true,
        token: rawToken,
        user: { id: user.id, username: user.username, role: user.role_name }
      });
    }

    // Check Admin Session for protected endpoints
    const admin = await getAdminUser();
    if (!admin && !path.startsWith("/notifications/ingest") && !path.startsWith("/devices/register")) {
      return sendJson({ success: false, error: "Unauthorized access" }, 401);
    }

    // 3. Dashboard Metrics
    if (path === "/admin/dashboard" && method === "GET") {
      const devTotal = await env.DB.prepare("SELECT COUNT(*) as c FROM devices").first();
      const devActive = await env.DB.prepare("SELECT COUNT(*) as c FROM devices WHERE status = 'ACTIVE'").first();
      const notifTotal = await env.DB.prepare("SELECT COUNT(*) as c FROM notification_events").first();
      const notifDelivered = await env.DB.prepare("SELECT COUNT(*) as c FROM notification_events WHERE status = 'DELIVERED'").first();
      const notifFailed = await env.DB.prepare("SELECT COUNT(*) as c FROM notification_events WHERE status = 'FAILED'").first();
      const lastHeartbeat = await env.DB.prepare("SELECT recorded_at FROM device_heartbeats ORDER BY recorded_at DESC LIMIT 1").first();

      return sendJson({
        success: true,
        metrics: {
          total_devices: devTotal?.c || 0,
          active_devices: devActive?.c || 0,
          offline_devices: (devTotal?.c || 0) - (devActive?.c || 0),
          total_notifications: notifTotal?.c || 0,
          delivered_notifications: notifDelivered?.c || 0,
          failed_notifications: notifFailed?.c || 0,
          last_heartbeat: lastHeartbeat?.recorded_at || "-"
        }
      });
    }

    // 4. Device Management
    if (path === "/admin/devices" && method === "GET") {
      const { results } = await env.DB.prepare(`
        SELECT d.device_id, d.device_name, d.status, d.last_heartbeat, d.last_delivery, d.registered_at, s.name as supervisor_name
        FROM devices d
        LEFT JOIN supervisors s ON d.supervisor_id = s.id
        ORDER BY d.registered_at DESC
      `).all();
      return sendJson({ success: true, devices: results });
    }

    if (path.match(/^\/admin\/devices\/[^\/]+\/status$/) && method === "POST") {
      const deviceId = path.split("/")[3];
      const { status } = await request.json();
      await env.DB.prepare("UPDATE devices SET status = ? WHERE device_id = ?").bind(status, deviceId).run();
      await env.DB.prepare("INSERT INTO audit_logs (actor_id, actor_type, action, target_resource, metadata) VALUES (?, 'USER', 'DEVICE_STATUS_CHANGE', ?, ?)")
        .bind(admin.user_id, deviceId, JSON.stringify({ new_status: status })).run();
      return sendJson({ success: true, status });
    }

    if (path.match(/^\/admin\/devices\/[^\/]+$/) && method === "DELETE") {
      const deviceId = path.split("/")[3];
      await env.DB.prepare("DELETE FROM devices WHERE device_id = ?").bind(deviceId).run();
      await env.DB.prepare("INSERT INTO audit_logs (actor_id, actor_type, action, target_resource, metadata) VALUES (?, 'USER', 'DELETE_DEVICE', ?, '{}')")
        .bind(admin.user_id, deviceId).run();
      return sendJson({ success: true });
    }

    // 5. Supervisor Management
    if (path === "/admin/supervisors" && method === "GET") {
      const { results } = await env.DB.prepare(`
        SELECT s.id, s.name, s.max_devices, s.status, s.created_at,
               t.chat_id,
               CASE WHEN t.bot_token IS NOT NULL THEN substr(t.bot_token, 1, 4) || '••••••••' || substr(t.bot_token, -4) ELSE NULL END as masked_token,
               (SELECT COUNT(*) FROM devices d WHERE d.supervisor_id = s.id) as current_devices
        FROM supervisors s
        LEFT JOIN telegram_configs t ON s.telegram_config_id = t.id
        ORDER BY s.created_at DESC
      `).all();
      return sendJson({ success: true, supervisors: results });
    }

    if (path === "/admin/supervisors" && method === "POST") {
      const { name, bot_token, chat_id, max_devices } = await request.json();
      if (!name || !bot_token || !chat_id) return sendJson({ success: false, error: "Missing required fields" }, 400);

      const supId = crypto.randomUUID();
      const tgId = crypto.randomUUID();
      const code = "ACT-" + Math.random().toString(36).substring(2, 8).toUpperCase();
      const codeHash = await sha256Hex(code);

      await env.DB.batch([
        env.DB.prepare("INSERT INTO telegram_configs (id, bot_token, chat_id, status) VALUES (?, ?, ?, 'ACTIVE')").bind(tgId, bot_token.trim(), chat_id.trim()),
        env.DB.prepare("INSERT INTO supervisors (id, supervisor_code, name, telegram_config_id, max_devices, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')").bind(supId, "SUP-" + code.slice(-4), name.trim(), tgId, Number(max_devices || 1)),
        env.DB.prepare("INSERT INTO activation_codes (id, code_hash, supervisor_id, usage_limit, used_count, expires_at, status) VALUES (?, ?, ?, ?, 0, datetime('now', '+30 days'), 'ACTIVE')").bind(crypto.randomUUID(), codeHash, supId, Number(max_devices || 1))
      ]);

      await env.DB.prepare("INSERT INTO audit_logs (actor_id, actor_type, action, target_resource, metadata) VALUES (?, 'USER', 'CREATE_SUPERVISOR', ?, ?)")
        .bind(admin.user_id, supId, JSON.stringify({ name, chat_id })).run();

      return sendJson({ success: true, supervisor_id: supId, activation_code: code });
    }

    // 6. Test Telegram Direct
    if (path === "/admin/telegram/test" && method === "POST") {
      const { bot_token, chat_id } = await request.json();
      const res = await sendTelegramDirect(bot_token, chat_id, "✅ <b>WT Relay Admin</b>: اختبار الاتصال بالخادم نجح بنسبة 100%.");
      return sendJson({ success: res.ok, httpStatus: res.httpStatus, description: res.description });
    }

    // 7. Notification Logs & Audit Logs
    if (path === "/admin/notifications" && method === "GET") {
      const { results } = await env.DB.prepare(`
        SELECT n.event_id, n.device_id, n.sender_title, n.status, n.received_at, n.processed_at,
               a.http_status, a.error_message
        FROM notification_events n
        LEFT JOIN delivery_attempts a ON a.event_id = n.event_id
        ORDER BY n.received_at DESC LIMIT 50
      `).all();
      return sendJson({ success: true, notifications: results });
    }

    if (path === "/admin/audit-logs" && method === "GET") {
      const { results } = await env.DB.prepare("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 50").all();
      return sendJson({ success: true, audit_logs: results });
    }

    return sendJson({ error: "Route Not Found" }, 404);
  }
};
