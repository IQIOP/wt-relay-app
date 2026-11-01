export async function enforceRateLimit(db, key, limit = 60, windowSeconds = 60) {
  const now = Math.floor(Date.now() / 1000);
  const existing = await db.prepare("SELECT window_start, request_count FROM rate_limits WHERE key = ?").bind(key).first();
  if (!existing || (now - existing.window_start) >= windowSeconds) {
    await db.prepare("INSERT OR REPLACE INTO rate_limits (key, window_start, request_count) VALUES (?, ?, 1)").bind(key, now).run();
    return true;
  }
  if (existing.request_count >= limit) {
    return false;
  }
  await db.prepare("UPDATE rate_limits SET request_count = request_count + 1 WHERE key = ?").bind(key).run();
  return true;
}
