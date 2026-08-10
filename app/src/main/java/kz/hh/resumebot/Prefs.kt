package kz.hh.resumebot

import android.content.Context

/**
 * Настройки приложения (SharedPreferences):
 * целевая страница, флаг авто-поднятия, опциональный Telegram.
 */
object Prefs {

    private const val FILE = "hh_bot_prefs"

    private const val KEY_URL = "target_url"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_TG_TOKEN = "tg_token"
    private const val KEY_TG_CHAT = "tg_chat"

    const val DEFAULT_URL = "https://hh.kz/applicant/resumes"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(ctx: Context): String =
        sp(ctx).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(ctx: Context, value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) sp(ctx).edit().putString(KEY_URL, v).apply()
    }

    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO, false)

    fun setAuto(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun tgToken(ctx: Context): String = sp(ctx).getString(KEY_TG_TOKEN, "") ?: ""

    fun setTgToken(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_TG_TOKEN, value.trim()).apply()
    }

    fun tgChat(ctx: Context): String = sp(ctx).getString(KEY_TG_CHAT, "") ?: ""

    fun setTgChat(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_TG_CHAT, value.trim()).apply()
    }
}
