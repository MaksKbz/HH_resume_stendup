package kz.hh.resumebot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Фоновый воркер: раз в 4 часа открывает страницу «Мои резюме»
 * в скрытом WebView (с сохранённой сессией) и жмёт «Поднять».
 *
 * WorkManager сам восстанавливает расписание после перезагрузки телефона.
 * Если фоновый клик не удался (сессия истекла, прошивка убила WebView и т.п.),
 * показываем уведомление — одно нажатие открывает приложение,
 * которое поднимет резюме в видимом WebView.
 */
class RaiseWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        val url = Prefs.url(appCtx)

        // Есть ли вообще сохранённая сессия
        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        if (cookies.isNullOrBlank()) {
            Notifier.notify(
                appCtx,
                "HH: нужен вход",
                "Откройте приложение и войдите в аккаунт один раз",
                openApp = true
            )
            return Result.success()
        }

        val r = try {
            withContext(Dispatchers.Main) { raiseViaHiddenWebView(url) }
        } catch (t: Throwable) {
            RaiseResult(0, 0, "error")
        }

        return if (r.clicked > 0) {
            Notifier.notify(
                appCtx,
                "✅ HH: резюме подняты",
                "Автоподнятие: нажато кнопок ${r.clicked}",
                openApp = false
            )
            Telegram.send(appCtx, "✅ HH: авто-поднятие, нажато кнопок: ${r.clicked}")
            Result.success()
        } else {
            Notifier.notify(
                appCtx,
                "HH: не удалось поднять",
                "Нажмите, чтобы открыть приложение — оно поднимет резюме",
                openApp = true
            )
            Telegram.send(appCtx, "⚠️ HH: фоновое поднятие не сработало (${r.texts})")
            Result.success()
        }
    }

    /**
     * Грузит страницу в WebView без окна (WebView требует главный поток —
     * поэтому doWork переключается на Dispatchers.Main, а здесь ждём
     * результат через корутину, не блокируя поток).
     */
    private suspend fun raiseViaHiddenWebView(url: String): RaiseResult =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var wv: WebView? = null

            fun finish(r: RaiseResult) {
                handler.removeCallbacksAndMessages(null)
                try { wv?.destroy() } catch (_: Throwable) { }
                if (cont.isActive) cont.resume(r)
            }

            try {
                val view = WebView(applicationContext)
                wv = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        when {
                            u.contains("/applicant/resumes") -> v.postDelayed({
                                v.evaluateJavascript(JsRaiser.CLICK_JS) { raw ->
                                    val r = JsRaiser.parse(raw)
                                    if (r.clicked > 0) {
                                        v.evaluateJavascript(JsRaiser.CONFIRM_JS, null)
                                    }
                                    v.postDelayed({ finish(r) }, 1500)
                                }
                            }, 2500)

                            u.contains("login") || u.contains("account") ->
                                finish(RaiseResult(0, 0, "auth"))
                        }
                    }
                }
                view.loadUrl(url)
            } catch (t: Throwable) {
                finish(RaiseResult(0, 0, "error"))
            }

            // Страховой таймаут, чтобы воркер не висел вечно
            handler.postDelayed({ finish(RaiseResult(0, 0, "timeout")) }, 70_000)
        }

    companion object {
        private const val WORK_NAME = "hh_resume_raise"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RaiseWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
