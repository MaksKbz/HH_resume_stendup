package kz.hh.resumebot

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Повтор сохранённого «рецепта» поднятия — чистые HTTP-запросы
 * БЕЗ браузера. Самый бережливый к батарее способ: несколько
 * маленьких запросов вместо запуска Chromium.
 *
 * Вызывать с фонового потока (Dispatchers.IO).
 */
object ReplayRunner {

    data class Outcome(val ok: Int, val total: Int, val reason: String)

    fun run(ctx: Context, pageUrl: String): Outcome {
        val recipeJson = Prefs.recipe(ctx)
        if (recipeJson.isBlank()) {
            return Outcome(
                0, 0,
                "нет сохранённого запроса — откройте приложение и поднимите вручную один раз"
            )
        }
        val arr = try {
            JSONArray(recipeJson)
        } catch (t: Throwable) {
            return Outcome(0, 0, "рецепт повреждён")
        }
        if (arr.length() == 0) return Outcome(0, 0, "рецепт пуст")

        val cookie = try {
            CookieManager.getInstance().getCookie(pageUrl)
        } catch (t: Throwable) {
            null
        }
        if (cookie.isNullOrBlank()) return Outcome(0, 0, "нет сессии (нужен вход)")

        // XSRF-токен из кук: если hh обновил его — подставим свежий в захваченные заголовки
        val xsrf = Regex("_xsrf=([^;]+)").find(cookie)?.groupValues?.get(1)

        // Origin/Referer по хосту целевой страницы
        val base = try {
            val u = URL(pageUrl); "${u.protocol}://${u.host}"
        } catch (t: Throwable) {
            pageUrl
        }
        val ua = Prefs.webUa(ctx).ifBlank {
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }

        var ok = 0
        var failReason = ""

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val method = o.optString("m", "POST")
            val rawUrl = o.optString("u")
            val body = if (o.isNull("b")) null else o.optString("b")
            val hdrs = if (o.isNull("h")) "" else o.optString("h")
            if (rawUrl.isBlank()) continue

            // относительные URL → абсолютные
            val reqUrl = if (rawUrl.startsWith("/")) base + rawUrl else rawUrl

            try {
                val conn = URL(reqUrl).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 12_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Referer", pageUrl)
                conn.setRequestProperty("Origin", base)

                // захваченные страницей заголовки (xsrf, content-type и т.п.)
                if (hdrs.isNotBlank()) {
                    try {
                        val hj = JSONObject(hdrs)
                        val keys = hj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            var v = hj.optString(k)
                            if (xsrf != null && k.contains("xsrf", ignoreCase = true)) v = xsrf
                            if (v.isNotBlank()) conn.setRequestProperty(k, v)
                        }
                    } catch (_: Throwable) { }
                }

                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    ok++
                } else {
                    failReason = "HTTP $code"
                    if (code == 401 || code == 403) {
                        return Outcome(ok, arr.length(), "сессия/доступ устарели (HTTP $code) — откройте приложение и войдите")
                    }
                }
            } catch (t: Throwable) {
                failReason = "сеть: ${t.javaClass.simpleName}"
            }

            Thread.sleep(400) // пауза между резюме
        }

        return if (ok > 0) {
            Outcome(ok, arr.length(), "ок" + if (failReason.isNotBlank()) ", были ошибки: $failReason" else "")
        } else {
            Outcome(0, arr.length(), if (failReason.isNotBlank()) failReason else "ничего не отправлено")
        }
    }
}
