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
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Фоновый воркер.
 *
 * Режим "фон": раз в 4 часа открывает страницу с резюме в скрытом WebView
 * (с сохранённой сессией) и жмёт «Поднять в поиске» для каждого резюме.
 *
 * Режим "тест": то же самое, но каждые 15 минут — для проверки, что фон работает.
 *
 * Каждый запуск пишется в текстовый лог (Prefs.addLog), а время последнего
 * удачного поднятия — в Prefs.lastRaiseOk (нужно для умного старта
 * после перезагрузки телефона). WorkManager сам восстанавливает
 * расписание после перезагрузки.
 */
class RaiseWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        val url = Prefs.url(appCtx)
        val source = inputData.getString(KEY_SOURCE) ?: "фон"
        val isTest = source == "тест"

        // Есть ли вообще сохранённая сессия
        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        if (cookies.isNullOrBlank()) {
            Prefs.addLog(appCtx, source, "нет сессии — нужен вход в приложении")
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

        val note = when {
            total > 0 -> "поднято резюме: $total"
            total == 0 -> "кнопок нет (лимит 4 ч или нужен вход)"
            else -> "ошибка выполнения"
        }
        Prefs.addLog(appCtx, source, note)
        if (total > 0) Prefs.setLastRaiseOk(appCtx)

        when {
            isTest -> Notifier.notify(
                appCtx,
                "🔔 HH: тест фона сработал",
                "Результат: $note",
                openApp = false
            )
            total > 0 -> Notifier.notify(
                appCtx,
                "✅ HH: поднято резюме: $total",
                "Автоподнятие прошло успешно",
                openApp = false
            )
            else -> Notifier.notify(
                appCtx,
                "HH: не удалось поднять",
                "Нажмите, чтобы открыть приложение — оно поднимет резюме",
                openApp = true
            )
        }
        return Result.success()
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
        private const val TEST_WORK_NAME = "hh_raise_test"
        private const val KEY_SOURCE = "source"

        /** Периодическое авто-поднятие каждые 4 часа. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RaiseWorker>(4, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_SOURCE to "фон"))
                .setConstraints(networkConstraint())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Тест-режим: то же самое, но каждые 15 минут (включается тумблером). */
        fun scheduleTest(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RaiseWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_SOURCE to "тест"))
                .setConstraints(networkConstraint())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(TEST_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancelTest(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(TEST_WORK_NAME)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }

        private fun networkConstraint() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
