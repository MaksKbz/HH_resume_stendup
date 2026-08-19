package kz.hh.resumebot

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
 *  3. ДОБИВКА — если hh отклонил часть запросов (обычно «ещё нельзя
 *     поднимать», резюме на замке), приходим добить их через ~18 минут.
 *  4. Уведомление с призывом открыть приложение.
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
        val retryNum = inputData.getInt(KEY_RETRY, 0)
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
        var repOk = -1
        var repTotal = -1
        var repLocked = 0
        var repBodyRej = 0
        var repCaptcha = false
        var repDetails = ""
        var repReason = ""

        // --- ШАГ 1: реплей запроса (экономно) ---
        try {
            val rep = withContext(Dispatchers.IO) { ReplayRunner.run(appCtx, url) }
            repOk = rep.ok
            repTotal = rep.total
            repLocked = rep.locked
            repBodyRej = rep.bodyRejected
            repCaptcha = rep.captcha
            repDetails = rep.details
            repReason = rep.reason
            if (rep.ok > 0) {
                raised = rep.ok
                note = buildString {
                    append("поднято запросами: ${rep.ok}/${rep.total}")
                    if (rep.locked > 0) append(" (замок по времени: ${rep.locked})")
                    if (rep.bodyRejected > 0) append(" (2xx-отказов: ${rep.bodyRejected})")
                }
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
        // диагностика: что ответил hh по каждому запросу (строкой выше результата)
        if (repDetails.isNotBlank()) Prefs.addLog(appCtx, source, "детали: $repDetails")

        // --- ШАГ 3: «добивка» ---
        // hh отклонил часть запросов (почти всегда — замок по времени у части
        // резюме: их таймеры разошлись с расписанием воркера). Придём добить
        // их через ~18 минут — само, без ручного вмешательства. Максимум
        // MAX_RETRY добивок подряд, дальше ждём обычного цикла.
        // В тест-режиме не нужно: воркер и так бегает каждые 15 минут.
        // При капче не дёргаемся: поможет только вход через браузер приложения.
        // Если ВСЕ ответы — «ещё нельзя» (замок 4 ч), добивка бесполезна
        // и лишь кормит антибот: ждём обычного цикла. Добивка нужна, когда
        // часть резюме поднялась, а часть нет — их замок вот-вот спадёт.
        val allLocked = repOk == 0 && repLocked >= repTotal && repTotal > 0
        if (!isTest && !repCaptcha && !allLocked && repTotal > 0 && repOk < repTotal &&
            !repReason.contains("устарели")
        ) {
            if (retryNum < MAX_RETRY) {
                scheduleRetry(appCtx, retryNum + 1)
                Prefs.addLog(
                    appCtx, source,
                    "добью остальные через ~18 минут (попытка ${retryNum + 1} из $MAX_RETRY)"
                )
            } else {
                Prefs.addLog(appCtx, source, "добивки кончились — следующий цикл по расписанию")
            }
        }
        // Полный успех обычного цикла — висящая добивка больше не нужна
        if (source == "фон" && repTotal > 0 && repOk == repTotal) cancelRetry(appCtx)

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
            repCaptcha -> Notifier.notify(
                appCtx,
                "🤖 HH: капча",
                "Откройте приложение и нажмите «Поднять сейчас» — капча пройдёт в браузере",
                openApp = true
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
        private const val RETRY_WORK_NAME = "hh_raise_retry"
        private const val KEY_SOURCE = "source"
        private const val KEY_RETRY = "retry"
        private const val MAX_RETRY = 2

        /**
         * Периодическое авто-поднятие. Интервал 4 ч 10 мин — сознательно
         * чуть БОЛЬШЕ лимита hh (4 ч): воркер, пришедший на секунды раньше
         * снятия замка, получал отказы и оставлял часть резюме висеть
         * (гонка таймеров, «поднято 3 из 5»).
         */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RaiseWorker>(250, TimeUnit.MINUTES)
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

        /** Одноразовая «добивка» через ~18 минут (после частичных отказов hh). */
        private fun scheduleRetry(ctx: Context, retryNum: Int) {
            val req = OneTimeWorkRequestBuilder<RaiseWorker>()
                .setInitialDelay(18, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_SOURCE to "добивка", KEY_RETRY to retryNum))
                .setConstraints(networkConstraint())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        private fun cancelRetry(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(RETRY_WORK_NAME)
        }

        private fun networkConstraint() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
