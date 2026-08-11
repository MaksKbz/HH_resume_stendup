package kz.hh.resumebot

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки и данные приложения (SharedPreferences):
 * целевая страница, флаги режимов, время последнего поднятия,
 * текстовый лог, сохранённый «рецепт» запроса поднятия и User-Agent.
 */
object Prefs {

    private const val FILE = "hh_bot_prefs"

    private const val KEY_URL = "target_url"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_TEST = "test_mode"
    private const val KEY_LAST_OK = "last_raise_ok"
    private const val KEY_LOG = "raise_log"
    private const val KEY_RECIPE = "raise_recipe"
    private const val KEY_WEB_UA = "web_ua"

    private const val MAX_LOG_LINES = 200

    const val URL_KZ = "https://hh.kz/applicant/profile/me"
    const val URL_RU = "https://hh.ru/applicant/profile/me"
    const val DEFAULT_URL = URL_KZ

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- Целевая страница / регион ----------

    fun url(ctx: Context): String =
        sp(ctx).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(ctx: Context, value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) sp(ctx).edit().putString(KEY_URL, v).apply()
    }

    // ---------- Флаги ----------

    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO, false)

    fun setAuto(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun testMode(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_TEST, false)

    fun setTestMode(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TEST, value).apply()
    }

    // ---------- Время последнего успешного поднятия ----------

    fun setLastRaiseOk(ctx: Context) {
        sp(ctx).edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply()
    }

    fun lastRaiseOk(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_OK, 0L)

    // ---------- Рецепт запроса поднятия (для фона без браузера) ----------

    fun setRecipe(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_RECIPE, json).apply()
    }

    fun recipe(ctx: Context): String = sp(ctx).getString(KEY_RECIPE, "") ?: ""

    // ---------- User-Agent встроенного браузера (чтобы реплей выглядел так же) ----------

    fun setWebUa(ctx: Context, ua: String) {
        if (ua.isNotBlank()) sp(ctx).edit().putString(KEY_WEB_UA, ua).apply()
    }

    fun webUa(ctx: Context): String = sp(ctx).getString(KEY_WEB_UA, "") ?: ""

    // ---------- Текстовый лог (вся история, новые сверху) ----------

    fun addLog(ctx: Context, source: String, result: String) {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "$time  |  $source  |  $result"
        val old = sp(ctx).getString(KEY_LOG, "") ?: ""
        val lines = (listOf(entry) + old.split("\n").filter { it.isNotBlank() })
            .take(MAX_LOG_LINES)
        sp(ctx).edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun getLog(ctx: Context): String = sp(ctx).getString(KEY_LOG, "") ?: ""

    fun clearLog(ctx: Context) {
        sp(ctx).edit().remove(KEY_LOG).apply()
    }
}
