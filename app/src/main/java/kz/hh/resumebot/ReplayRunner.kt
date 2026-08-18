package kz.hh.resumebot

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Повтор сохранённого «рецепта» поднятия — чистые HTTP-запросы
 * БЕЗ браузера. Самый бережливый к батарее способ: несколько
 * маленьких запросов вместо запуска Chromium.
 *
 * v1.8 (диагностика):
 *  — hh отвечает HTTP 200 даже когда НЕ поднимает (отказ в тексте
 *    ответа). Поэтому: читаем тело каждого ответа, «успех» с текстом
 *    отказа считаем 2xx-ОТКАЗОМ, а не успехом;
 *  — в лог пишем детали по каждому запросу: адрес → код → фрагмент
 *    ответа. По ним станет ясно, какой запрос — настоящее поднятие,
 *    а какой — мусор, и что hh пишет при отказе;
 *  — лёгкий GET страницы перед реплеем (свежие куки/_xsrf) — как в v1.7.
 *
 * Вызывать с фонового потока (Dispatchers.IO).
 */
object ReplayRunner {

    data class Outcome(
        val ok: Int,
        val total: Int,
        val reason: String,
        val locked: Int = 0,       // HTTP 429
        val bodyRejected: Int = 0, // HTTP 2xx, но в тексте ответа — отказ
        val details: String = ""   // «адрес→код «фрагмент»; …» по каждому запросу
    )

    /** Адреса телеметрии/логов — к поднятию отношения не имеют. */
    private val TELEMETRY = Regex(
        "metric|analytic|counter|valet|sentry|beacon|telemetry|tracking|/log|" +
            "clck|yandex|google-analytics|impression|pixel|shifter|webvisor|metrika"
    )

    /** Маркеры отказа в теле «успешного» (HTTP 200) ответа. */
    private val ERRWORDS = Regex(
        "(?i)(ошиб|нельз|слишком рано|рано подня|уже поднят|too_early|too often|" +
            "\"error\"\\s*:\\s*[{\\\"]|\"errors\"\\s*:\\s*\\[\\s*\\{)"
    )

    /**
     * Чистит перехваченный трафик: оставляет только настоящие запросы
     * поднятия. Признак: адрес содержит resum/raise ИЛИ в заголовках
     * есть xsrf-токен, при этом адрес не похож на телеметрию.
     * Дубли (адрес+тело) выкидываем. Если фильтр ничего не нашёл —
     * возвращаем исходный рецепт как есть (count = -1).
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

    /** Адреса рецепта без домена — для лога (увидеть, что реально перехвачено). */
    fun recipeShapes(rawJson: String): String {
        return try {
            val arr = JSONArray(rawJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var u = o.optString("u").replace(Regex("^https?://[^/]+"), "")
                if (u.length > 60) u = u.take(60) + "…"
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(u)
                if (sb.length > 220) { sb.append(" …"); break }
            }
            sb.toString()
        } catch (t: Throwable) { "" }
    }

    private fun readCap(stream: InputStream?, cap: Int): String {
        if (stream == null) return ""
        return try {
            val buf = ByteArray(cap)
            var total = 0
            while (total < cap) {
                val r = stream.read(buf, total, cap - total)
                if (r < 0) break
                total += r
            }
            String(buf, 0, total, Charsets.UTF_8)
        } catch (_: Throwable) { "" }
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
        var bodyRejected = 0
        var failReason = ""
        val detList = ArrayList<String>()

        fun addDetail(short: String, code: Int, snip: String) {
            if (detList.size >= 7) return
            detList.add(
                short + "→" + code + if (snip.isNotBlank()) " «" + snip.take(45) + "»" else ""
            )
        }

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
            val short = reqUrl.removePrefix(base).let { if (it.length > 70) it.take(70) + "…" else it }

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

                // читаем тело ответа (немного) — hh прячет отказ внутри «200 OK»
                val respText = readCap(
                    if (code in 200..299) conn.inputStream
                    else (conn.errorStream ?: conn.inputStream),
                    600
                ).replace(Regex("\\s+"), " ").trim()

                when {
                    code in 200..299 -> {
                        if (ERRWORDS.containsMatchIn(respText)) {
                            bodyRejected++
                            if (failReason.isBlank()) failReason = "2xx с отказом в тексте"
                            addDetail(short, code, respText)
                        } else {
                            ok++
                            addDetail(short, code, respText.take(45))
                        }
                    }
                    code == 429 -> { // «ещё нельзя поднимать» — замок по времени
                        locked++
                        failReason = "замок по времени (429)"
                        addDetail(short, code, respText.take(45))
                    }
                    code == 401 || code == 403 -> {
                        addDetail(short, code, respText.take(45))
                        conn.disconnect()
                        return Outcome(
                            ok, arr.length(),
                            "сессия/доступ устарели (HTTP $code) — откройте приложение и войдите",
                            locked, bodyRejected, joinDetails(detList, arr.length())
                        )
                    }
                    else -> {
                        failReason = "HTTP $code"
                        addDetail(short, code, respText.take(45))
                    }
                }
                conn.disconnect()
            } catch (t: Throwable) {
                failReason = "сеть: ${t.javaClass.simpleName}"
                addDetail(short, -1, t.javaClass.simpleName)
            }

            Thread.sleep(400) // пауза между запросами
        }

        val details = joinDetails(detList, arr.length())
        return if (ok > 0) {
            val extra = mutableListOf<String>()
            if (locked > 0) extra.add("замок по времени: $locked")
            if (bodyRejected > 0) extra.add("2xx-отказов: $bodyRejected")
            if (failReason.isNotBlank() && failReason != "замок по времени (429)" &&
                failReason != "2xx с отказом в тексте"
            ) extra.add(failReason)
            Outcome(
                ok, arr.length(),
                if (extra.isEmpty()) "ок" else "частично: " + extra.joinToString("; "),
                locked, bodyRejected, details
            )
        } else {
            val reason = when {
                bodyRejected > 0 -> "hh ответил отказом (см. строку «детали»)"
                locked > 0 && failReason == "замок по времени (429)" ->
                    "все ещё на замке по времени (429 ×$locked) — hh разрешит позже"
                failReason.isNotBlank() -> failReason
                else -> "ничего не отправлено"
            }
            Outcome(0, arr.length(), reason, locked, bodyRejected, details)
        }
    }

    private fun joinDetails(list: List<String>, total: Int): String {
        if (list.isEmpty()) return ""
        val more = if (total > list.size) "; …ещё ${total - list.size}" else ""
        return list.joinToString("; ") + more
    }
}
