export async function sendTelegramMessage(botToken, chatId, text) {
  const url = `https://api.telegram.org/bot${botToken}/sendMessage`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text: text,
      parse_mode: "HTML",
      disable_web_page_preview: true
    })
  });
  const data = await response.json().catch(() => ({ ok: false, description: "Invalid JSON from Telegram" }));
  return {
    ok: response.ok && data.ok === true,
    status: response.status,
    error: data.description || null
  };
}
