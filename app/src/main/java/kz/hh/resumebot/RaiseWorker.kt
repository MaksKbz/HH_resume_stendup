package kz.hh.resumebot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
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

/** Итог фонового прогона: сколько поднято + причина, если не вышло. */
private data class WorkerOutcome(val total: Int, val reason: String)

/**
 * Фоновый воркер.
 *
 * Режим "фон": раз в 4 часа открывает страницу с резюме в скрытом WebView
 * (с сохранённой сессией) и жмёт «Поднять в поиске» для каждого резюме.
 *
 * Режим "тест": то же самое, но каждые 15 минут — для проверки фона.
 *
 * Каждый запуск пишется в текстовый лог с ТОЧНОЙ причиной неудачи
 * (таймаут / исключение / редирект на вход / кнопки не найдены) — это
 * позволяет диагностировать фон без отладчика.
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

        Prefs.addLog(appCtx, source, "запуск воркера")

        // Есть ли вообще сохранённая сессия
        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        if (cookies.isNullOrBlank()) {
            Prefs.addLog(appCtx, source, "не поднято: нет сессии (нужен вход в приложении)")
            Notifier.notify(
                appCtx,
                "HH: нужен вход",
                "Откройте приложение и войдите в аккаунт один раз",
                openApp = true
            )
            return Result.success()
        }

        val outcome = try {
            withContext(Dispatchers.Main) { raiseViaHiddenWebView(url) }
        } catch (t: Throwable) {
            WorkerOutcome(-1, "исключение: ${t.javaClass.simpleName}")
        }

        val note = if (outcome.total > 0) {
            "поднято резюме: ${outcome.total}"
        } else {
            "не поднято: ${outcome.reason}"
        }
        Prefs.addLog(appCtx, source, note)
        if (outcome.total > 0) Prefs.setLastRaiseOk(appCtx)

        when {
            isTest -> Notifier.notify(
                appCtx,
                if (outcome.total > 0) "🔔 HH: тест фона — УСПЕХ" else "🔔 HH: тест фона",
                note,
                openApp = outcome.total <= 0
            )
            outcome.total > 0 -> Notifier.notify(
                appCtx,
                "✅ HH: поднято резюме: ${outcome.total}",
                "Автоподнятие прошло успешно",
                openApp = false
            )
            else -> Notifier.notify(
                appCtx,
                "HH: не удалось поднять",
                "$note. Нажмите, чтобы открыть приложение",
                openApp = true
            )
        }
        return Result.success()
    }

    /**
     * Грузит страницу в WebView без окна.
     *
     * Важные детали для фона на любых прошивках:
     *  - задаём WebView НЕНУЛЕВОЙ размер (иначе hh не отрисовывает кнопки:
     *    layout/rвnder не идёт при 0x0, и переходы «повисают»);
     *  - программный рендер (нет GPU-окна в фоне);
     *  - onResume/resumeTimers.
     *
     * Причина каждой неудачи попадает в WorkerOutcome.reason → в лог.
     */
    private suspend fun raiseViaHiddenWebView(url: String): WorkerOutcome =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var wv: WebView? = null

            fun finish(o: WorkerOutcome) {
                handler.removeCallbacksAndMessages(null)
                try { wv?.destroy() } catch (_: Throwable) { }
                if (cont.isActive) cont.resume(o)
            }

            try {
                val view = WebView(applicationContext)
                wv = view

                try { view.setLayerType(View.LAYER_TYPE_SOFTWARE, null) } catch (_: Throwable) { }
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                try { view.resumeTimers() } catch (_: Throwable) { }
                try { view.onResume() } catch (_: Throwable) { }

                // Фиктивные «экранные» размеры, чтобы SPA отрисовал контент
                val w = 1080
                val h = 1920
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, w, h)

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        when {
                            // Сначала логин: backUrl-параметр может содержать
                            // "/applicant/", поэтому порядок проверок важен
                            u.contains("login") || u.contains("account") ->
                                finish(WorkerOutcome(0, "редирект на страницу входа"))

                            u.contains("/applicant/") -> v.postDelayed({
                                RaiseDriver.runChain(v) { total, locked ->
                                    val reason = if (locked > 0) {
                                        "уже подняты, след. окно позже (замков: $locked)"
                                    } else {
                                        "кнопки не найдены на странице"
                                    }
                                    v.postDelayed({
                                        finish(WorkerOutcome(total, if (total > 0) "ок" else reason))
                                    }, 1000)
                                }
                            }, 2500)
                        }
                    }
                }
                view.loadUrl(url)
            } catch (t: Throwable) {
                finish(WorkerOutcome(-1, "исключение: ${t.javaClass.simpleName}: ${t.message}"))
            }

            // Страховой таймаут, чтобы воркер не висел вечно
            handler.postDelayed(
                { finish(WorkerOutcome(-1, "таймаут 80с: страница не прогрузилась в фоне")) },
                80_000
            )
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
