export async function writeAuditLog(db, actorId, actorType, action, targetResource, metadataObj = {}) {
  await db.prepare(`
    INSERT INTO audit_logs (actor_id, actor_type, action, target_resource, metadata)
    VALUES (?, ?, ?, ?, ?)
  `).bind(actorId, actorType, action, targetResource, JSON.stringify(metadataObj)).run();
}

export async function ensureBootstrapAdmin(env) {
  if (!env.BOOTSTRAP_ADMIN_HASH) return;
  const existing = await env.DB.prepare("SELECT id FROM users WHERE username = 'admin'").first();
  if (!existing) {
    await env.DB.prepare(`
      INSERT INTO users (id, username, password_hash, role_id, status)
      VALUES ('usr_bootstrap_admin', 'admin', ?, 'role_admin', 'ACTIVE')
    `).bind(env.BOOTSTRAP_ADMIN_HASH).run();
  }
}
