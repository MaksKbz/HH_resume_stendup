package kz.hh.resumebot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Запуск WebView в фоне через НЕВИДИМОЕ окно-оверлей.
 *
 * Зачем: WebView, просто созданный в памяти (без окна), на многих прошивках
 * в фоне не загружает страницу — Chromium не стартует рендер-процесс,
 * пока view не прикреплён к настоящему окну. Оверлей решает это:
 * прозрачное окно 360x640 уводится за пределы экрана (x = -3000),
 * пользователь его не видит, а WebView считает себя «настоящим».
 *
 * Требует разрешение SYSTEM_ALERT_WINDOW («Показ поверх других приложений»).
 * Вызывать ТОЛЬКО с главного потока.
 */
object OverlayRunner {

    data class Outcome(val total: Int, val reason: String)

    fun overlaysAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun run(ctx: Context, url: String, onDone: (Outcome) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var wv: WebView? = null
        var finished = false

        fun finish(o: Outcome) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            try { wv?.let { wm.removeView(it) } } catch (_: Throwable) { }
            try { wv?.destroy() } catch (_: Throwable) { }
            onDone(o)
        }

        try {
            val view = WebView(ctx)
            wv = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            try { view.resumeTimers() } catch (_: Throwable) { }
            try { view.onResume() } catch (_: Throwable) { }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                360, 640, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                x = -3000          // уводим за пределы экрана
                y = 0
                alpha = 0.05f      // почти прозрачно (0.0 некоторые GPU пропускают)
            }

            wm.addView(view, lp)

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    when {
                        // Сначала логин: backUrl-параметр может содержать
                        // "/applicant/", поэтому порядок проверок важен
                        u.contains("login") || u.contains("account") ->
                            finish(Outcome(0, "редирект на страницу входа"))

                        u.contains("/applicant/") -> v.postDelayed({
                            RaiseDriver.runChain(v) { total, locked ->
                                val reason = if (locked > 0) {
                                    "уже подняты, след. окно позже (замков: $locked)"
                                } else {
                                    "кнопки не найдены на странице"
                                }
                                v.postDelayed({
                                    finish(Outcome(total, if (total > 0) "ок" else reason))
                                }, 1000)
                            }
                        }, 2500)
                    }
                }
            }
            view.loadUrl(url)
        } catch (t: Throwable) {
            finish(Outcome(-1, "исключение: ${t.javaClass.simpleName}: ${t.message}"))
        }

        // Страховой таймаут, чтобы процесс не висел вечно
        handler.postDelayed(
            { finish(Outcome(-1, "таймаут 90с: страница не прогрузилась даже в оверлее")) },
            90_000
        )
    }
}
