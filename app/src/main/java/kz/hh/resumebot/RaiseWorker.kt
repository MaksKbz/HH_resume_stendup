package kz.hh.resumebot

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Фоновый воркер.
 *
 * Порядок попыток:
 *  1. РЕПЛЕЙ — повтор захваченного при ручном поднятии HTTP-запроса.
 *     Без браузера, доли секунды, почти не ест батарею.
 *  2. ОВЕРЛЕЙ-WebView — только если реплей не вышел (запасной путь).
 *  3. Уведомление с призывом открыть приложение.
 *
 * Каждый запуск пишется в текстовый лог с точным результатом.
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

        // --- есть ли сохранённая сессия ---
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

        var raised = 0
        var note = ""

        // --- ШАГ 1: реплей запроса (экономно) ---
        try {
            val rep = withContext(Dispatchers.IO) { ReplayRunner.run(appCtx, url) }
            if (rep.ok > 0) {
                raised = rep.ok
                note = "поднято запросами: ${rep.ok}/${rep.total}"
            } else {
                note = "реплей: ${rep.reason}"
            }
        } catch (e: CancellationException) {
            Prefs.addLog(appCtx, source, "прервано системой")
            return Result.success()
        } catch (t: Throwable) {
            note = "реплей: исключение ${t.javaClass.simpleName}"
        }

        // --- ШАГ 2: оверлей-WebView (запасной путь, только если есть разрешение) ---
        if (raised == 0 && OverlayRunner.overlaysAllowed(appCtx)) {
            try {
                val o = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        OverlayRunner.run(appCtx, url) { res ->
                            if (cont.isActive) cont.resume(res)
                        }
                    }
                }
                if (o.total > 0) {
                    raised = o.total
                    note = (if (note.isBlank()) "" else "$note; ") + "оверлей: поднято ${o.total}"
                } else {
                    note = (if (note.isBlank()) "" else "$note; ") + "оверлей: ${o.reason}"
                }
            } catch (e: CancellationException) {
                Prefs.addLog(appCtx, source, "прервано системой (оверлей)")
                return Result.success()
            } catch (t: Throwable) {
                note = (if (note.isBlank()) "" else "$note; ") + "оверлей: исключение ${t.javaClass.simpleName}"
            }
        }

        if (raised > 0) Prefs.setLastRaiseOk(appCtx)
        Prefs.addLog(appCtx, source, if (raised > 0) note else "не поднято: $note")

        when {
            isTest -> Notifier.notify(
                appCtx,
                if (raised > 0) "🔔 HH: тест фона — УСПЕХ!" else "🔔 HH: тест фона",
                if (raised > 0) note else "не поднято: $note",
                openApp = raised <= 0
            )
            raised > 0 -> Notifier.notify(
                appCtx,
                "✅ HH: поднято резюме: $raised",
                note,
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
