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
 * Фоновый воркер: раз в 4 часа открывает страницу с резюме
 * в скрытом WebView (с сохранённой сессией) и жмёт «Поднять в поиске»
 * для каждого доступного резюме.
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

        val total = try {
            withContext(Dispatchers.Main) { raiseViaHiddenWebView(url) }
        } catch (t: Throwable) {
            -1
        }

        return if (total > 0) {
            Notifier.notify(
                appCtx,
                "✅ HH: поднято резюме: $total",
                "Автоподнятие прошло успешно",
                openApp = false
            )
            Result.success()
        } else {
            Notifier.notify(
                appCtx,
                "HH: не удалось поднять",
                "Нажмите, чтобы открыть приложение — оно поднимет резюме",
                openApp = true
            )
            Result.success()
        }
    }

    /**
     * Грузит страницу в WebView без окна (WebView требует главный поток —
     * поэтому doWork переключается на Dispatchers.Main, а здесь ждём
     * результат через корутину, не блокируя поток).
     */
    private suspend fun raiseViaHiddenWebView(url: String): Int =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var wv: WebView? = null

            fun finish(total: Int) {
                handler.removeCallbacksAndMessages(null)
                try { wv?.destroy() } catch (_: Throwable) { }
                if (cont.isActive) cont.resume(total)
            }

            try {
                val view = WebView(applicationContext)
                wv = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        when {
                            // Сначала проверяем логин: backUrl-параметр может
                            // содержать "/applicant/", так что порядок важен
                            u.contains("login") || u.contains("account") ->
                                finish(0)

                            u.contains("/applicant/") -> v.postDelayed({
                                RaiseDriver.runChain(v) { total ->
                                    v.postDelayed({ finish(total) }, 1000)
                                }
                            }, 2500)
                        }
                    }
                }
                view.loadUrl(url)
            } catch (t: Throwable) {
                finish(-1)
            }

            // Страховой таймаут, чтобы воркер не висел вечно
            handler.postDelayed({ finish(-1) }, 80_000)
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
