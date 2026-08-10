package kz.hh.resumebot

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Опциональная отправка уведомлений в Telegram-бота. */
object Telegram {

    fun send(ctx: Context, message: String) {
        val token = Prefs.tgToken(ctx)
        val chat = Prefs.tgChat(ctx)
        if (token.isBlank() || chat.isBlank()) return

        Thread {
            try {
                val conn = URL("https://api.telegram.org/bot$token/sendMessage")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = "chat_id=" + URLEncoder.encode(chat, "UTF-8") +
                    "&text=" + URLEncoder.encode(message, "UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode // читаем ответ, чтобы запрос ушёл
                conn.disconnect()
            } catch (_: Throwable) {
                // сеть недоступна — просто молча пропускаем
            }
        }.start()
    }
}
