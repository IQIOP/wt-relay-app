import { corsHeaders, jsonResponse, errorResponse } from "./middleware/errorHandler.js";
import { enforceRateLimit } from "./middleware/rateLimiter.js";
import { authenticateUser, authenticateDevice } from "./middleware/auth.js";
import { sha256Hex, generateSecureCode, verifyPassword } from "./services/cryptoService.js";
import { sendTelegramMessage } from "./services/telegramService.js";
import { writeAuditLog, ensureBootstrapAdmin } from "./db/queries.js";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const method = request.method;
    const clientIp = request.headers.get("CF-Connecting-IP") || "local";

    try {
      await ensureBootstrapAdmin(env);

      const allowed = await enforceRateLimit(env.DB, `${clientIp}:${path}`, 120, 60);
      if (!allowed) {
        return errorResponse("RATE_LIMITED", "Too many requests", "طلبات كثيرة جداً، حاول لاحقاً", 429);
      }

      // 1. GET /health
      if (path === "/health" && method === "GET") {
        const dbCheck = await env.DB.prepare("SELECT 1 as ok").first();
        return jsonResponse({
          success: true,
          status: dbCheck?.ok === 1 ? "ONLINE" : "DEGRADED",
          database: dbCheck?.ok === 1 ? "CONNECTED" : "ERROR",
          timestamp: new Date().toISOString()
        });
      }

      // 2. POST /auth/login
      if (path === "/auth/login" && method === "POST") {
        const body = await request.json();
        const username = (body.username || "").trim();
        const password = body.password || "";
        if (!username || !password) {
          return errorResponse("VALIDATION_ERROR", "Username and password required", "اسم المستخدم وكلمة المرور مطلوبان", 400);
        }

        const user = await env.DB.prepare("SELECT * FROM users WHERE username = ? AND status = 'ACTIVE'").bind(username).first();
        if (!user || !(await verifyPassword(password, user.password_hash))) {
          return errorResponse("INVALID_CREDENTIALS", "Invalid credentials", "بيانات الدخول غير صحيحة", 401);
        }

        const rawToken = `${crypto.randomUUID()}-${generateSecureCode(16)}`;
        const tokenHash = await sha256Hex(rawToken);
        const expiresAt = new Date(Date.now() + 1000 * 60 * 60 * 24 * 7).toISOString();

        await env.DB.prepare("INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)").bind(tokenHash, user.id, expiresAt).run();
        await writeAuditLog(env.DB, user.id, "USER", "LOGIN", "auth/login", { username });

        return jsonResponse({
          success: true,
          token: rawToken,
          expires_at: expiresAt,
          user: { id: user.id, username: user.username, role_id: user.role_id }
        });
      }

      // 3. POST /auth/logout
      if (path === "/auth/logout" && method === "POST") {
        const auth = await authenticateUser(request, env);
        if (auth.error) return auth.error;
        await env.DB.prepare("DELETE FROM user_sessions WHERE token_hash = ?").bind(auth.tokenHash).run();
        await writeAuditLog(env.DB, auth.user.user_id, "USER", "LOGOUT", "auth/logout");
        return jsonResponse({ success: true });
      }

      // 4. GET /auth/session
      if (path === "/auth/session" && method === "GET") {
        const auth = await authenticateUser(request, env);
        if (auth.error) return auth.error;
        return jsonResponse({
          success: true,
          session: {
            user_id: auth.user.user_id,
            username: auth.user.username,
            role: auth.user.role_name,
            expires_at: auth.user.expires_at
          }
        });
      }

      // 5. POST /telegram/test
      if (path === "/telegram/test" && method === "POST") {
        const auth = await authenticateUser(request, env, "telegram:test");
        if (auth.error) return auth.error;
        const { bot_token, chat_id } = await request.json();
        if (!bot_token || !chat_id) {
          return errorResponse("VALIDATION_ERROR", "Bot token and chat ID required", "توكن البوت ومعرف القناة مطلوبان", 400);
        }
        const tgRes = await sendTelegramMessage(bot_token.trim(), chat_id.trim(), "<b>WT Relay</b>: Connection verified.");
        if (!tgRes.ok) {
          return errorResponse("TELEGRAM_FAILED", tgRes.error || "Telegram rejected message", "فشل الاتصال بتليجرام", 400);
        }
        return jsonResponse({ success: true, status: "CONNECTED" });
      }

      // 6. GET & POST /supervisors
      if (path === "/supervisors" && method === "GET") {
        const auth = await authenticateUser(request, env, "supervisors:read");
        if (auth.error) return auth.error;
        const { results } = await env.DB.prepare(`
          SELECT s.id, s.supervisor_code, s.name, s.max_devices, s.status, s.created_at,
                 t.chat_id, t.status as telegram_status,
                 (SELECT COUNT(*) FROM devices d WHERE d.supervisor_id = s.id) as current_devices
          FROM supervisors s
          LEFT JOIN telegram_configs t ON s.telegram_config_id = t.id
          ORDER BY s.created_at DESC
        `).all();
        return jsonResponse({ success: true, supervisors: results });
      }

      if (path === "/supervisors" && method === "POST") {
        const auth = await authenticateUser(request, env, "supervisors:write");
        if (auth.error) return auth.error;
        const { name, bot_token, chat_id, max_devices } = await request.json();
        if (!name?.trim() || !bot_token?.trim() || !chat_id?.trim() || !(Number(max_devices) > 0)) {
          return errorResponse("VALIDATION_ERROR", "All supervisor fields are required", "جميع حقول المشرف مطلوبة", 400);
        }

        const supId = crypto.randomUUID();
        const supCode = `SUP-${generateSecureCode(3)}`;
        const tgId = crypto.randomUUID();
        const rawActivationCode = generateSecureCode(6);
        const codeHash = await sha256Hex(rawActivationCode);
        const actId = crypto.randomUUID();
        const expiresAt = new Date(Date.now() + 1000 * 60 * 60 * 24 * 30).toISOString();

        await env.DB.batch([
          env.DB.prepare("INSERT INTO telegram_configs (id, bot_token, chat_id, status) VALUES (?, ?, ?, 'CONNECTED')").bind(tgId, bot_token.trim(), chat_id.trim()),
          env.DB.prepare("INSERT INTO supervisors (id, supervisor_code, name, telegram_config_id, max_devices, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')").bind(supId, supCode, name.trim(), tgId, Number(max_devices)),
          env.DB.prepare("INSERT INTO activation_codes (id, code_hash, supervisor_id, usage_limit, used_count, expires_at, status) VALUES (?, ?, ?, ?, 0, ?, 'ACTIVE')").bind(actId, codeHash, supId, Number(max_devices), expiresAt)
        ]);

        await writeAuditLog(env.DB, auth.user.user_id, "USER", "CREATE_SUPERVISOR", supId, { supCode, name: name.trim() });

        return jsonResponse({
          success: true,
          supervisor: { id: supId, supervisor_code: supCode, name: name.trim(), max_devices: Number(max_devices) },
          activation_code: rawActivationCode,
          expires_at: expiresAt
        }, 201);
      }

      // 7. POST /activation/generate
      if (path === "/activation/generate" && method === "POST") {
        const auth = await authenticateUser(request, env, "activation:write");
        if (auth.error) return auth.error;
        const { supervisor_id, usage_limit = 1, valid_days = 7 } = await request.json();
        const sup = await env.DB.prepare("SELECT id FROM supervisors WHERE id = ?").bind(supervisor_id).first();
        if (!sup) return errorResponse("NOT_FOUND", "Supervisor not found", "المشرف غير موجود", 404);

        const rawCode = generateSecureCode(6);
        const codeHash = await sha256Hex(rawCode);
        const actId = crypto.randomUUID();
        const expiresAt = new Date(Date.now() + 1000 * 60 * 60 * 24 * Number(valid_days)).toISOString();

        await env.DB.prepare(`
          INSERT INTO activation_codes (id, code_hash, supervisor_id, usage_limit, used_count, expires_at, status)
          VALUES (?, ?, ?, ?, 0, ?, 'ACTIVE')
        `).bind(actId, codeHash, supervisor_id, Number(usage_limit), expiresAt).run();

        await writeAuditLog(env.DB, auth.user.user_id, "USER", "GENERATE_ACTIVATION", actId, { supervisor_id });
        return jsonResponse({ success: true, activation_id: actId, activation_code: rawCode, expires_at: expiresAt }, 201);
      }

      // 8. POST /activation/validate
      if (path === "/activation/validate" && method === "POST") {
        const { activation_code } = await request.json();
        if (!activation_code) return errorResponse("VALIDATION_ERROR", "Activation code required", "رمز التفعيل مطلوب", 400);
        const codeHash = await sha256Hex(activation_code.trim().toUpperCase());
        const record = await env.DB.prepare(`
          SELECT a.*, s.name as supervisor_name, s.max_devices
          FROM activation_codes a
          JOIN supervisors s ON a.supervisor_id = s.id
          WHERE a.code_hash = ? AND a.status = 'ACTIVE'
        `).bind(codeHash).first();

        if (!record || record.used_count >= record.usage_limit || new Date(record.expires_at).getTime() <= Date.now()) {
          return errorResponse("INVALID_CODE", "Activation code invalid or expired", "رمز التفعيل غير صالح أو منتهي", 400);
        }
        return jsonResponse({
          success: true,
          valid: true,
          supervisor_id: record.supervisor_id,
          supervisor_name: record.supervisor_name
        });
      }

      // 9. POST /devices/register
      if (path === "/devices/register" && method === "POST") {
        const { device_id, device_name, app_version, activation_code } = await request.json();
        if (!device_id || !device_name || !app_version || !activation_code) {
          return errorResponse("VALIDATION_ERROR", "Missing registration parameters", "بيانات تسجيل الجهاز ناقصة", 400);
        }
        const codeHash = await sha256Hex(activation_code.trim().toUpperCase());
        const act = await env.DB.prepare(`
          SELECT a.*, s.max_devices
          FROM activation_codes a
          JOIN supervisors s ON a.supervisor_id = s.id
          WHERE a.code_hash = ? AND a.status = 'ACTIVE'
        `).bind(codeHash).first();

        if (!act || act.used_count >= act.usage_limit || new Date(act.expires_at).getTime() <= Date.now()) {
          return errorResponse("INVALID_CODE", "Invalid or exhausted activation code", "رمز التفعيل غير صالح أو مستنفد", 400);
        }

        const countRow = await env.DB.prepare("SELECT COUNT(*) as total FROM devices WHERE supervisor_id = ? AND device_id != ?").bind(act.supervisor_id, device_id).first();
        if (countRow.total >= act.max_devices) {
          return errorResponse("DEVICE_LIMIT_REACHED", "Supervisor device limit reached", "تم الوصول للحد الأقصى للأجهزة", 403);
        }

        const rawDeviceToken = `dvc_${crypto.randomUUID()}_${generateSecureCode(12)}`;
        const deviceTokenHash = await sha256Hex(rawDeviceToken);
        const nowIso = new Date().toISOString();

        await env.DB.batch([
          env.DB.prepare(`
            INSERT INTO devices (device_id, supervisor_id, device_name, app_version, device_token_hash, status, last_heartbeat, registered_at)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
              supervisor_id = excluded.supervisor_id,
              device_name = excluded.device_name,
              app_version = excluded.app_version,
              device_token_hash = excluded.device_token_hash,
              status = 'ACTIVE',
              last_heartbeat = excluded.last_heartbeat
          `).bind(device_id, act.supervisor_id, device_name.trim(), app_version.trim(), deviceTokenHash, nowIso, nowIso),
          env.DB.prepare(`
            UPDATE activation_codes
            SET used_count = used_count + 1,
                status = CASE WHEN used_count + 1 >= usage_limit THEN 'CONSUMED' ELSE 'ACTIVE' END
            WHERE id = ?
          `).bind(act.id)
        ]);

        await writeAuditLog(env.DB, device_id, "DEVICE", "REGISTER_DEVICE", act.supervisor_id, { device_name });
        return jsonResponse({
          success: true,
          device_id,
          supervisor_id: act.supervisor_id,
          device_token: rawDeviceToken,
          status: "ACTIVE"
        }, 201);
      }

      // 10. GET /devices & GET /devices/:id & POST /devices/:id/heartbeat
      if (path === "/devices" && method === "GET") {
        const auth = await authenticateUser(request, env, "devices:read");
        if (auth.error) return auth.error;
        const { results } = await env.DB.prepare(`
          SELECT d.device_id, d.supervisor_id, s.name as supervisor_name, d.device_name, d.app_version, d.status, d.last_heartbeat, d.last_delivery, d.registered_at
          FROM devices d
          JOIN supervisors s ON d.supervisor_id = s.id
          ORDER BY d.registered_at DESC
        `).all();
        return jsonResponse({ success: true, devices: results });
      }

      const heartbeatMatch = path.match(/^\/devices\/([^\/]+)\/heartbeat$/);
      if (heartbeatMatch && method === "POST") {
        const deviceAuth = await authenticateDevice(request, env);
        if (deviceAuth.error) return deviceAuth.error;
        const targetId = decodeURIComponent(heartbeatMatch[1]);
        if (deviceAuth.device.device_id !== targetId) {
          return errorResponse("FORBIDDEN", "Device ID mismatch", "عدم تطابق معرف الجهاز", 403);
        }
        const body = await request.json().catch(() => ({}));
        const nowIso = new Date().toISOString();

        await env.DB.batch([
          env.DB.prepare("UPDATE devices SET last_heartbeat = ? WHERE device_id = ?").bind(nowIso, targetId),
          env.DB.prepare("INSERT INTO device_heartbeats (device_id, battery_level, network_type, recorded_at) VALUES (?, ?, ?, ?)").bind(targetId, body.battery_level ?? null, body.network_type ?? "UNKNOWN", nowIso)
        ]);

        return jsonResponse({ success: true, last_heartbeat: nowIso });
      }

      const deviceDetailMatch = path.match(/^\/devices\/([^\/]+)$/);
      if (deviceDetailMatch && method === "GET") {
        const auth = await authenticateUser(request, env, "devices:read");
        if (auth.error) return auth.error;
        const targetId = decodeURIComponent(deviceDetailMatch[1]);
        const device = await env.DB.prepare(`
          SELECT d.device_id, d.supervisor_id, s.name as supervisor_name, d.device_name, d.app_version, d.status, d.last_heartbeat, d.last_delivery, d.registered_at
          FROM devices d
          JOIN supervisors s ON d.supervisor_id = s.id
          WHERE d.device_id = ?
        `).bind(targetId).first();
        if (!device) return errorResponse("NOT_FOUND", "Device not found", "الجهاز غير موجود", 404);
        return jsonResponse({ success: true, device });
      }

      // 11. POST /notifications/ingest
      if (path === "/notifications/ingest" && method === "POST") {
        const deviceAuth = await authenticateDevice(request, env);
        if (deviceAuth.error) return deviceAuth.error;

        const { event_id, package_name, sender_title, message_text, received_at } = await request.json();
        if (!event_id || !package_name || !sender_title || !message_text) {
          return errorResponse("VALIDATION_ERROR", "Missing notification payload fields", "بيانات الإشعار ناقصة", 400);
        }

        // Server-side Idempotency Check
        const existingEvent = await env.DB.prepare("SELECT event_id, status FROM notification_events WHERE event_id = ?").bind(event_id).first();
        if (existingEvent && existingEvent.status === "DELIVERED") {
          return jsonResponse({ success: true, idempotent_duplicate: true, event_id, status: "DELIVERED" });
        }

        const contentHash = await sha256Hex(`${package_name}|${sender_title}|${message_text}`);
        const tgConfig = await env.DB.prepare("SELECT * FROM telegram_configs WHERE id = ?").bind(deviceAuth.device.telegram_config_id).first();
        if (!tgConfig) {
          return errorResponse("TELEGRAM_NOT_CONFIGURED", "Supervisor has no Telegram config", "لم يتم ضبط إعدادات تليجرام للمشرف", 400);
        }

        const receivedIso = received_at || new Date().toISOString();
        await env.DB.prepare(`
          INSERT OR IGNORE INTO notification_events (event_id, device_id, package_name, sender_title, content_hash, status, received_at)
          VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
        `).bind(event_id, deviceAuth.device.device_id, package_name, sender_title, contentHash, receivedIso).run();

        const formattedText = `<b>📲 WT Relay Notification</b>\n<b>Device:</b> ${deviceAuth.device.device_name}\n<b>From:</b> ${sender_title}\n<b>Message:</b>\n${message_text}`;
        const delivery = await sendTelegramMessage(tgConfig.bot_token, tgConfig.chat_id, formattedText);
        const nowIso = new Date().toISOString();
        const finalStatus = delivery.ok ? "DELIVERED" : "FAILED";

        await env.DB.batch([
          env.DB.prepare("UPDATE notification_events SET status = ?, processed_at = ? WHERE event_id = ?").bind(finalStatus, nowIso, event_id),
          env.DB.prepare("INSERT INTO delivery_attempts (event_id, http_status, error_message, success) VALUES (?, ?, ?, ?)").bind(event_id, delivery.status, delivery.error, delivery.ok ? 1 : 0)
        ]);

        if (delivery.ok) {
          await env.DB.prepare("UPDATE devices SET last_delivery = ? WHERE device_id = ?").bind(nowIso, deviceAuth.device.device_id).run();
          return jsonResponse({ success: true, event_id, status: "DELIVERED" });
        } else {
          return errorResponse("DELIVERY_FAILED", delivery.error || "Failed to relay to Telegram", "فشل تسليم الإشعار إلى تليجرام", 502);
        }
      }

      // 12. GET /notifications & GET /notifications/:id
      if (path === "/notifications" && method === "GET") {
        const auth = await authenticateUser(request, env, "notifications:read");
        if (auth.error) return auth.error;
        const { results } = await env.DB.prepare(`
          SELECT e.event_id, e.device_id, d.device_name, e.package_name, e.sender_title, e.status, e.received_at, e.processed_at
          FROM notification_events e
          JOIN devices d ON e.device_id = d.device_id
          ORDER BY e.received_at DESC
          LIMIT 100
        `).all();
        return jsonResponse({ success: true, notifications: results });
      }

      const notifDetailMatch = path.match(/^\/notifications\/([^\/]+)$/);
      if (notifDetailMatch && method === "GET") {
        const auth = await authenticateUser(request, env, "notifications:read");
        if (auth.error) return auth.error;
        const eventId = decodeURIComponent(notifDetailMatch[1]);
        const event = await env.DB.prepare("SELECT * FROM notification_events WHERE event_id = ?").bind(eventId).first();
        if (!event) return errorResponse("NOT_FOUND", "Event not found", "الإشعار غير موجود", 404);
        const { results: attempts } = await env.DB.prepare("SELECT * FROM delivery_attempts WHERE event_id = ? ORDER BY attempt_time DESC").bind(eventId).all();
        return jsonResponse({ success: true, event, delivery_attempts: attempts });
      }

      // 13. GET /audit-logs
      if (path === "/audit-logs" && method === "GET") {
        const auth = await authenticateUser(request, env, "audit:read");
        if (auth.error) return auth.error;
        const { results } = await env.DB.prepare("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 100").all();
        return jsonResponse({ success: true, audit_logs: results });
      }

      return errorResponse("NOT_FOUND", `Route ${method} ${path} not found`, "المسار غير موجود", 404);
    } catch (err) {
      return errorResponse("INTERNAL_ERROR", err.message || "Internal server error", "خطأ داخلي في الخادم", 500);
    }
  }
};
