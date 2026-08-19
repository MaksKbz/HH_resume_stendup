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
 * Фоновое поднятие чистыми HTTP-запросами БЕЗ браузера.
 *
 * Протокол hh (выяснен диагностикой v1.8):
 *  — настоящий запрос поднятия: POST …/profile/shards/resume/touch
 *    (хост resume-profile-front.hh.kz / hh.ru), id резюме — В ТЕЛЕ;
 *  — 2xx без маркеров ошибки   → поднято;
 *  — 409 / 429                 → «ещё нельзя» (замок 4 ч) — не ошибка, добьём позже;
 *  — 302/303 со ссылкой на /account/captcha → антибот hh: надо разово
 *    открыть приложение и поднять из браузера.
 *
 * Запросы строим ДЛЯ КАЖДОГО id резюме со страницы: в рецепт попадают
 * только те резюме, что поднимали при его записи, а поднимать надо все.
 * Мусорные запросы (метрика Гугла и прочая телеметрия) в фон не шлём.
 *
 * Вызывать с фонового потока (Dispatchers.IO).
 */
object ReplayRunner {

    data class Outcome(
        val ok: Int,
        val total: Int,
        val reason: String,
        val locked: Int = 0,       // 409/429 — замок по времени
        val bodyRejected: Int = 0, // 2xx, но в тексте ответа — отказ
        val captcha: Boolean = false,
        val details: String = ""   // «touch/abc123→code «фрагмент»; …»
    )

    /** Адреса телеметрии/логов — к поднятию отношения не имеют. */
    private val TELEMETRY = Regex(
        "metric|analytic|counter|valet|sentry|beacon|telemetry|tracking|/log|" +
            "clck|yandex|impression|pixel|shifter|webvisor|metrika|" +
            "google|gstatic|doubleclick"
    )

    /**
     * Извлекает id резюме из тел перехваченных touch-запросов.
     * Это гарантированно СВОИ резюме (их поднимали в браузере приложения),
     * в отличие от парсинга страницы, который цепляет и чужие карточки.
     */
    fun mineIdsFromRecipe(recipeJson: String): List<String> {
        return try {
            val arr = JSONArray(recipeJson)
            val out = LinkedHashSet<String>()
            val hex = Regex("[0-9a-fA-F]{20,}")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optString("u").contains(TOUCH_MARK)) continue
                val b = if (o.isNull("b")) "" else o.optString("b")
                for (m in hex.findAll(b)) out.add(m.value)
            }
            out.toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Маркеры отказа в теле «успешного» (HTTP 200) ответа. */
    private val ERRWORDS = Regex(
        "(?i)(ошиб|нельз|слишком рано|рано подня|уже поднят|too_early|too often|" +
            "\"error\"\\s*:\\s*[{\\\"]|\"errors\"\\s*:\\s*\\[\\s*\\{)"
    )

    /** Настоящий запрос поднятия. */
    private const val TOUCH_MARK = "/resume/touch"

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

        // --- id всех резюме (собраны приложением со страницы профиля) ---
        val ids: List<String> = try {
            val a = JSONArray(Prefs.resumeIds(ctx))
            (0 until a.length()).mapNotNull {
                a.optString(it).takeIf { s -> s.isNotBlank() }
            }
        } catch (t: Throwable) {
            emptyList()
        }

        // --- Шаблон touch-запроса из рецепта (последний перехваченный) ---
        var template: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("u").contains(TOUCH_MARK)) template = o
        }

        // --- Список запросов к отправке: ровно по одному на каждое резюме ---
        // label — короткое имя для лога (кусочек id), req — запрос
        val plan = ArrayList<Pair<String, JSONObject>>()
        if (template != null) {
            val tBody = if (template.isNull("b")) "" else template.optString("b")
            // id, который сидит в шаблоне: сначала ищем среди известных, иначе — hex-токен
            var tId = ids.firstOrNull { tBody.contains(it) }
            if (tId == null && tBody.isNotBlank()) {
                tId = Regex("[0-9a-fA-F]{20,}").find(tBody)?.value
            }
            if (ids.isNotEmpty() && tId != null) {
                for (id in ids) {
                    val clone = JSONObject(template.toString())
                    clone.put("b", tBody.replace(tId, id))
                    plan.add(id.take(6) to clone)
                }
            } else {
                // id не распознали — шлём шаблон как есть
                plan.add("?" to template)
            }
        } else {
            // в рецепте нет touch — старый путь: весь рецепт как есть
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                plan.add("#${i + 1}" to o)
            }
        }
        if (plan.isEmpty()) return Outcome(0, 0, "нечего отправлять")

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
            if (detList.size >= 8) return
            detList.add(
                short + "→" + code + if (snip.isNotBlank()) " «" + snip.take(45) + "»" else ""
            )
        }

        for ((label, req) in plan) {
            val method = req.optString("m", "POST")
            val rawUrl = req.optString("u")
            var body = if (req.isNull("b")) null else req.optString("b")
            val hdrs = if (req.isNull("h")) "" else req.optString("h")
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
            val short = "touch/$label"

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
                        if (respText.contains("captcha", ignoreCase = true)) {
                            addDetail(short, code, "капча!")
                            conn.disconnect()
                            return captchaOutcome(ok, plan.size, locked, bodyRejected, detList)
                        }
                        if (ERRWORDS.containsMatchIn(respText)) {
                            bodyRejected++
                            if (failReason.isBlank()) failReason = "2xx с отказом в тексте"
                            addDetail(short, code, respText)
                        } else {
                            ok++
                            addDetail(short, code, respText.take(45))
                        }
                    }
                    code == 409 || code == 429 -> {
                        // «ещё нельзя поднять» — замок по времени, не сбой
                        locked++
                        failReason = "ещё нельзя поднять ($code)"
                        addDetail(short, code, respText.take(45))
                    }
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: ""
                        val isCaptcha = loc.contains("captcha", true) ||
                            respText.contains("captcha", true)
                        addDetail(short, code, if (isCaptcha) "капча!" else loc.take(45))
                        conn.disconnect()
                        if (isCaptcha) {
                            return captchaOutcome(ok, plan.size, locked, bodyRejected, detList)
                        }
                        if (failReason.isBlank()) failReason = "редирект HTTP $code"
                    }
                    code == 401 || code == 403 -> {
                        addDetail(short, code, respText.take(45))
                        conn.disconnect()
                        return Outcome(
                            ok, plan.size,
                            "сессия/доступ устарели (HTTP $code) — откройте приложение и войдите",
                            locked, bodyRejected, false, joinDetails(detList, plan.size)
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

            Thread.sleep(700) // пауза между резюме — не провоцируем антибот
        }

        val details = joinDetails(detList, plan.size)
        return if (ok > 0) {
            val extra = mutableListOf<String>()
            if (locked > 0) extra.add("ещё нельзя: $locked")
            if (bodyRejected > 0) extra.add("2xx-отказов: $bodyRejected")
            if (failReason.isNotBlank() &&
                !failReason.startsWith("ещё нельзя") &&
                failReason != "2xx с отказом в тексте"
            ) extra.add(failReason)
            Outcome(
                ok, plan.size,
                if (extra.isEmpty()) "ок" else "частично: " + extra.joinToString("; "),
                locked, bodyRejected, false, details
            )
        } else {
            val reason = when {
                bodyRejected > 0 -> "hh ответил отказом (см. строку «детали»)"
                locked > 0 && failReason.startsWith("ещё нельзя") ->
                    "все ещё на замке по времени (×$locked) — hh разрешит позже"
                failReason.isNotBlank() -> failReason
                else -> "ничего не отправлено"
            }
            Outcome(0, plan.size, reason, locked, bodyRejected, false, details)
        }
    }

    private fun captchaOutcome(
        ok: Int, total: Int, locked: Int, bodyRejected: Int, detList: List<String>
    ): Outcome = Outcome(
        ok, total,
        "hh показал капчу — откройте приложение и нажмите «Поднять сейчас» один раз",
        locked, bodyRejected, true, joinDetails(detList, total)
    )

    private fun joinDetails(list: List<String>, total: Int): String {
        if (list.isEmpty()) return ""
        val more = if (total > list.size) "; …ещё ${total - list.size}" else ""
        return list.joinToString("; ") + more
    }
}
