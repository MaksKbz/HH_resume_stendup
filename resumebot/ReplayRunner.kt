package kz.hh.resumebot

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Повтор сохранённого «рецепта» поднятия — чистые HTTP-запросы
 * БЕЗ браузера. Самый бережливый к батарее способ: несколько
 * маленьких запросов вместо запуска Chromium.
 *
 * v1.7:
 *  — перед реплеем делаем лёгкий GET страницы кабинета: сервер выдаёт
 *    СВЕЖИЕ куки и свежий _xsrf (hh ротирует его, за день протухает);
 *  — свежий _xsrf подставляем и в заголовки, и в тело, и в query;
 *  — HTTP 429 («ещё нельзя поднимать») считаем отдельно: это замок
 *    по времени, а не сбой — воркер планирует «добивку» позже;
 *  — в отчёт при ошибках прикладываем кусочек ответа сервера.
 *
 * Вызывать с фонового потока (Dispatchers.IO).
 */
object ReplayRunner {

    data class Outcome(
        val ok: Int,
        val total: Int,
        val reason: String,
        val locked: Int = 0 // сколько запросов hh отклонил по времени (HTTP 429)
    )

    /** Адреса телеметрии/логов — к поднятию отношения не имеют. */
    private val TELEMETRY = Regex(
        "metric|analytic|counter|valet|sentry|beacon|telemetry|tracking|/log|" +
            "clck|yandex|google-analytics|impression|pixel|shifter|webvisor|metrika"
    )

    /**
     * Чистит перехваченный трафик: оставляет только настоящие запросы
     * поднятия. Сниффер на странице записывает ВСЕ POST'ы подряд —
     * вместе со служебной телеметрией hh, которая всегда отвечает 200
     * и из-за этого искажает счётчик успеха («19/19» при 5 резюме).
     * Признак поднятия: адрес содержит resum/raise ИЛИ в заголовках
     * есть xsrf-токен (hh им подписывает настоящие действия),
     * при этом адрес не похож на телеметрию. Дубли
     * (адрес+тело) выкидываем.
     *
     * Возвращает (json рецепта, сколько в нём запросов поднятия).
     * Если фильтр ничего не нашёл (hh вдруг сменил адреса) — возвращаем
     * исходный рецепт как есть и count = -1: лучше «грязный» рецепт,
     * чем совсем никакого.
     */
    fun filterRecipe(rawJson: String): Pair<String, Int> {
        val src = try {
            JSONArray(rawJson)
        } catch (t: Throwable) {
            return Pair(rawJson, -1)
        }
        val out = JSONArray()
        val seen = HashSet<String>()
        var count = 0
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (u.isBlank()) continue
            val ul = u.lowercase(Locale.ROOT)
            val h = if (o.isNull("h")) "" else o.optString("h").lowercase(Locale.ROOT)
            if (TELEMETRY.containsMatchIn(ul)) continue
            val looksRaise =
                ul.contains("resum") || ul.contains("raise") || h.contains("xsrf")
            if (!looksRaise) continue
            val b = if (o.isNull("b")) "" else o.optString("b")
            if (!seen.add("$u|$b")) continue
            out.put(o)
            count++
        }
        return if (count == 0) Pair(rawJson, -1) else Pair(out.toString(), count)
    }

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

        val rawCookie = try {
            CookieManager.getInstance().getCookie(pageUrl)
        } catch (t: Throwable) {
            null
        }
        if (rawCookie.isNullOrBlank()) return Outcome(0, 0, "нет сессии (нужен вход)")

        val base = try {
            val u = URL(pageUrl); "${u.protocol}://${u.host}"
        } catch (t: Throwable) {
            pageUrl
        }
        val ua = Prefs.webUa(ctx).ifBlank {
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }

        // --- «банка» кук: стартовые — из WebView, дальше пополняется из Set-Cookie ---
        val jar = LinkedHashMap<String, String>()
        fun putCookie(pair: String) {
            val eq = pair.indexOf('=')
            if (eq > 0) jar[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
        rawCookie.split(";").forEach { putCookie(it) }

        fun cookieHeader() = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun mergeSetCookie(conn: HttpURLConnection) {
            try {
                for ((k, values) in conn.headerFields) {
                    if (k?.equals("Set-Cookie", ignoreCase = true) == true && values != null) {
                        for (line in values) putCookie(line.substringBefore(';'))
                    }
                }
            } catch (_: Throwable) { }
        }

        // --- ШАГ 0: лёгкий GET страницы — сервер выдаст свежие куки и _xsrf ---
        try {
            val get = URL(pageUrl).openConnection() as HttpURLConnection
            get.requestMethod = "GET"
            get.connectTimeout = 10_000
            get.readTimeout = 10_000
            get.instanceFollowRedirects = false
            get.setRequestProperty("Cookie", cookieHeader())
            get.setRequestProperty("User-Agent", ua)
            get.responseCode // триггер выполнения запроса
            mergeSetCookie(get)
            get.disconnect()
        } catch (_: Throwable) { /* не вышло — продолжим со старыми куками */ }

        var xsrf = jar["_xsrf"]

        var ok = 0
        var locked = 0
        var failReason = ""
        var failSnippet = ""

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val method = o.optString("m", "POST")
            val rawUrl = o.optString("u")
            var body = if (o.isNull("b")) null else o.optString("b")
            val hdrs = if (o.isNull("h")) "" else o.optString("h")
            if (rawUrl.isBlank()) continue

            // относительные URL → абсолютные; свежий _xsrf в query
            var reqUrl = if (rawUrl.startsWith("/")) base + rawUrl else rawUrl
            if (xsrf != null && reqUrl.contains("_xsrf=")) {
                reqUrl = reqUrl.replace(Regex("([?&]_xsrf=)[^&]*"), "$1$xsrf")
            }
            // свежий _xsrf в теле (form-urlencoded или json)
            if (xsrf != null && body != null) {
                body = body
                    .replace(Regex("_xsrf=[^&]*"), "_xsrf=$xsrf")
                    .replace(Regex("\"xsrf\"\\s*:\\s*\"[^\"]*\""), "\"xsrf\":\"$xsrf\"")
            }

            try {
                val conn = URL(reqUrl).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 12_000
                conn.readTimeout = 12_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Cookie", cookieHeader())
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
                mergeSetCookie(conn)
                xsrf = jar["_xsrf"] ?: xsrf

                when {
                    code in 200..299 -> ok++
                    code == 429 -> { // «ещё нельзя поднимать» — замок по времени
                        locked++
                        failReason = "замок по времени (429)"
                    }
                    code == 401 || code == 403 -> {
                        conn.disconnect()
                        return Outcome(
                            ok, arr.length(),
                            "сессия/доступ устарели (HTTP $code) — откройте приложение и войдите",
                            locked
                        )
                    }
                    else -> {
                        failReason = "HTTP $code"
                        if (failSnippet.isBlank()) {
                            failSnippet = try {
                                val stream = conn.errorStream ?: conn.inputStream
                                stream?.readBytes()?.take(160)?.toByteArray()
                                    ?.toString(Charsets.UTF_8)
                                    ?.replace(Regex("\\s+"), " ")
                                    ?.take(120) ?: ""
                            } catch (_: Throwable) { "" }
                        }
                    }
                }
                conn.disconnect()
            } catch (t: Throwable) {
                failReason = "сеть: ${t.javaClass.simpleName}"
            }

            Thread.sleep(400) // пауза между запросами
        }

        val snippet = if (failSnippet.isBlank()) "" else ": $failSnippet"
        return if (ok > 0) {
            val extra = mutableListOf<String>()
            if (locked > 0) extra.add("замок по времени: $locked")
            if (failReason.isNotBlank() && failReason != "замок по времени (429)") {
                extra.add(failReason + snippet)
            }
            Outcome(
                ok, arr.length(),
                if (extra.isEmpty()) "ок" else "частично: " + extra.joinToString("; "),
                locked
            )
        } else {
            val reason = when {
                locked > 0 && failReason == "замок по времени (429)" ->
                    "все ещё на замке по времени (429 ×$locked) — hh разрешит позже"
                failReason.isNotBlank() -> failReason + snippet
                else -> "ничего не отправлено"
            }
            Outcome(0, arr.length(), reason, locked)
        }
    }
}
