import { sha256Hex } from "../services/cryptoService.js";
import { errorResponse } from "./errorHandler.js";

export async function authenticateUser(request, env, requiredPermission = null) {
  const authHeader = request.headers.get("Authorization") || "";
  if (!authHeader.startsWith("Bearer ")) {
    return { error: errorResponse("UNAUTHORIZED", "Missing Bearer token", "رمز الجلسة مفقود", 401) };
  }
  const rawToken = authHeader.slice(7).trim();
  const tokenHash = await sha256Hex(rawToken);

  const session = await env.DB.prepare(`
    SELECT s.user_id, s.expires_at, u.username, u.status, r.name as role_name, r.permissions
    FROM user_sessions s
    JOIN users u ON s.user_id = u.id
    JOIN roles r ON u.role_id = r.id
    WHERE s.token_hash = ?
  `).bind(tokenHash).first();

  if (!session || session.status !== "ACTIVE") {
    return { error: errorResponse("INVALID_SESSION", "Invalid or inactive session", "جلسة غير صالحة", 401) };
  }

  if (new Date(session.expires_at).getTime() <= Date.now()) {
    await env.DB.prepare("DELETE FROM user_sessions WHERE token_hash = ?").bind(tokenHash).run();
    return { error: errorResponse("SESSION_EXPIRED", "Session expired", "انتهت صلاحية الجلسة", 401) };
  }

  if (requiredPermission) {
    const perms = JSON.parse(session.permissions || "[]");
    const hasPerm = perms.includes("*") || perms.includes(requiredPermission) || perms.includes(`${requiredPermission.split(":")[0]}:*`);
    if (!hasPerm) {
      return { error: errorResponse("FORBIDDEN", "Insufficient permissions", "صلاحيات غير كافية", 403) };
    }
  }

  return { user: session, tokenHash };
}

export async function authenticateDevice(request, env) {
  const rawToken = request.headers.get("X-Device-Token") || "";
  if (!rawToken) {
    return { error: errorResponse("DEVICE_UNAUTHORIZED", "Missing device token", "رمز الجهاز مفقود", 401) };
  }
  const tokenHash = await sha256Hex(rawToken);
  const device = await env.DB.prepare(`
    SELECT d.*, s.telegram_config_id, s.status as supervisor_status
    FROM devices d
    JOIN supervisors s ON d.supervisor_id = s.id
    WHERE d.device_token_hash = ?
  `).bind(tokenHash).first();

  if (!device) {
    return { error: errorResponse("INVALID_DEVICE", "Device not registered", "الجهاز غير مسجل", 401) };
  }
  if (device.status !== "ACTIVE" || device.supervisor_status !== "ACTIVE") {
    return { error: errorResponse("DEVICE_SUSPENDED", "Device or supervisor suspended", "الجهاز أو المشرف موقوف", 403) };
  }
  return { device };
}
